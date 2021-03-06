/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.intellij.build.impl

import com.intellij.openapi.util.io.FileFilters
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.JvmArchitecture
import org.jetbrains.intellij.build.WindowsDistributionCustomizer
import org.jetbrains.jps.model.module.JpsModuleSourceRoot

/**
 * @author nik
 */
class WindowsDistributionBuilder {
  private final BuildContext buildContext
  private final WindowsDistributionCustomizer customizer
  final String winDistPath

  WindowsDistributionBuilder(BuildContext buildContext, WindowsDistributionCustomizer customizer) {
    this.customizer = customizer
    this.buildContext = buildContext
    winDistPath = "$buildContext.paths.buildOutputRoot/dist.win"
  }

  //todo[nik] rename
  void layoutWin(File ideaProperties) {
    buildContext.messages.progress("Building distributions for Windows")
    buildContext.ant.copy(todir: "$winDistPath/bin") {
      fileset(dir: "$buildContext.paths.communityHome/bin/win") {
        if (!buildContext.includeBreakGenLibraries()) {
          exclude(name: "breakgen*")
        }
      }
      if (buildContext.productProperties.yourkitAgentBinariesDirectoryPath != null) {
        fileset(dir: buildContext.productProperties.yourkitAgentBinariesDirectoryPath) {
          include(name: "yjpagent*.dll")
        }
      }
    }
    buildContext.ant.copy(file: ideaProperties.path, todir: "$winDistPath/bin")
    buildContext.ant.fixcrlf(file: "$winDistPath/bin/idea.properties", eol: "dos")

    if (customizer.icoPath != null) {
      buildContext.ant.copy(file: customizer.icoPath, tofile: "$winDistPath/bin/${buildContext.productProperties.baseFileName}.ico")
    }
    if (customizer.includeBatchLaunchers) {
      winScripts()
    }
    winVMOptions()
    buildWinLauncher(JvmArchitecture.x32)
    buildWinLauncher(JvmArchitecture.x64)
    customizer.copyAdditionalFiles(buildContext, winDistPath)
    new File(winDistPath, "bin").listFiles(FileFilters.filesWithExtension("exe"))?.each {
      buildContext.signExeFile(it.absolutePath)
    }

    def arch = customizer.bundledJreArchitecture
    def jreDirectoryPath = arch != null ? buildContext.bundledJreManager.extractWinJre(arch) : null
    buildWinZip(jreDirectoryPath, buildContext.productProperties.buildCrossPlatformDistribution ? ".win" : "")
    if (arch != null && customizer.buildZipWithBundledOracleJre) {
      String oracleJrePath = buildContext.bundledJreManager.extractOracleWinJre(arch)
      if (oracleJrePath != null) {
        buildWinZip(oracleJrePath, "-oracle-win")
      }
      else {
        buildContext.messages.warning("Skipping building Windows zip archive with bundled Oracle JRE because JRE archive is missing")
      }
    }

    buildContext.executeStep("Build Windows Exe Installer", BuildOptions.WINDOWS_EXE_INSTALLER_STEP) {
      new WinExeInstallerBuilder(buildContext, customizer, jreDirectoryPath).buildInstaller(winDistPath)
    }
  }

  //todo[nik] rename
  private void winScripts() {
    String fullName = buildContext.applicationInfo.productName
    //todo[nik] looks like names without .exe were also supported, do we need this?
    String vmOptionsFileName = "${buildContext.productProperties.baseFileName}%BITS%.exe"

    String classPath = "SET CLASS_PATH=%IDE_HOME%\\lib\\${buildContext.bootClassPathJarNames[0]}\n"
    classPath += buildContext.bootClassPathJarNames[1..-1].collect { "SET CLASS_PATH=%CLASS_PATH%;%IDE_HOME%\\lib\\$it" }.join("\n")
    if (buildContext.productProperties.toolsJarRequired) {
      classPath += "\nSET CLASS_PATH=%CLASS_PATH%;%JDK%\\lib\\tools.jar"
    }

    def batName = "${buildContext.productProperties.baseFileName}.bat"
    buildContext.ant.copy(todir: "$winDistPath/bin") {
      fileset(dir: "$buildContext.paths.communityHome/bin/scripts/win")

      filterset(begintoken: "@@", endtoken: "@@") {
        filter(token: "product_full", value: fullName)
        filter(token: "product_uc", value: buildContext.productProperties.environmentVariableBaseName(buildContext.applicationInfo))
        filter(token: "vm_options", value: vmOptionsFileName)
        filter(token: "isEap", value: buildContext.applicationInfo.isEAP)
        filter(token: "system_selector", value: buildContext.systemSelector)
        filter(token: "ide_jvm_args", value: buildContext.additionalJvmArguments)
        filter(token: "class_path", value: classPath)
        filter(token: "script_name", value: batName)
      }
    }

    if (batName != "idea.bat") {
      //todo[nik] rename idea.bat in sources to something more generic
      buildContext.ant.move(file: "$winDistPath/bin/idea.bat", tofile: "$winDistPath/bin/$batName")
    }
    String inspectScript = buildContext.productProperties.inspectCommandName
    if (inspectScript != "inspect") {
      String targetPath = "$winDistPath/bin/${inspectScript}.bat"
      buildContext.ant.move(file: "$winDistPath/bin/inspect.bat", tofile: targetPath)
      buildContext.patchInspectScript(targetPath)
    }


    buildContext.ant.fixcrlf(srcdir: "$winDistPath/bin", includes: "*.bat", eol: "dos")
  }

  //todo[nik] rename
  private void winVMOptions() {
    JvmArchitecture.values().each {
      def yourkitSessionName = buildContext.applicationInfo.isEAP && buildContext.productProperties.enableYourkitAgentInEAP ? buildContext.systemSelector : null
      def fileName = "${buildContext.productProperties.baseFileName}${it.fileSuffix}.exe.vmoptions"
      new File(winDistPath, "bin/$fileName").text = VmOptionsGenerator.computeVmOptions(it, buildContext.applicationInfo.isEAP, yourkitSessionName).replace(' ', '\n') + "\n"
    }

    buildContext.ant.fixcrlf(srcdir: "$winDistPath/bin", includes: "*.vmoptions", eol: "dos")
  }

  private void buildWinLauncher(JvmArchitecture arch) {
    buildContext.messages.block("Build Windows executable ${arch.name()}") {
      String exeFileName = "${buildContext.productProperties.baseFileName}${arch.fileSuffix}.exe"
      def launcherPropertiesPath = "${buildContext.paths.temp}/launcher${arch.fileSuffix}.properties"
      def upperCaseProductName = buildContext.applicationInfo.upperCaseProductName
      def lowerCaseProductName = buildContext.applicationInfo.shortProductName.toLowerCase()
      String vmOptions = "$buildContext.additionalJvmArguments -Didea.paths.selector=${buildContext.systemSelector}".trim()
      def productName = buildContext.applicationInfo.shortProductName

      String jdkEnvVarSuffix = arch == JvmArchitecture.x64 ? "_64" : "";
      def envVarBaseName = buildContext.productProperties.environmentVariableBaseName(buildContext.applicationInfo)
      new File(launcherPropertiesPath).text = """
IDS_JDK_ONLY=$buildContext.productProperties.toolsJarRequired
IDS_JDK_ENV_VAR=${envVarBaseName}_JDK$jdkEnvVarSuffix
IDS_APP_TITLE=$productName Launcher
IDS_VM_OPTIONS_PATH=%USERPROFILE%\\\\.$buildContext.systemSelector
IDS_VM_OPTION_ERRORFILE=-XX:ErrorFile=%USERPROFILE%\\\\java_error_in_${lowerCaseProductName}_%p.log
IDS_VM_OPTION_HEAPDUMPPATH=-XX:HeapDumpPath=%USERPROFILE%\\\\java_error_in_${lowerCaseProductName}.hprof
IDC_WINLAUNCHER=${upperCaseProductName}_LAUNCHER
IDS_PROPS_ENV_VAR=${envVarBaseName}_PROPERTIES
IDS_VM_OPTIONS_ENV_VAR=$envVarBaseName${arch.fileSuffix}_VM_OPTIONS
IDS_ERROR_LAUNCHING_APP=Error launching $productName
IDS_VM_OPTIONS=$vmOptions
""".trim()

      def communityHome = "$buildContext.paths.communityHome"
      String inputPath = "$communityHome/bin/WinLauncher/WinLauncher${arch.fileSuffix}.exe"
      def outputPath = "$winDistPath/bin/$exeFileName"
      def resourceModules = [buildContext.findApplicationInfoModule(), buildContext.findModule("icons")]
      buildContext.ant.java(classname: "com.pme.launcher.LauncherGeneratorMain", fork: "true", failonerror: "true") {
        sysproperty(key: "java.awt.headless", value: "true")
        arg(value: inputPath)
        arg(value: buildContext.findApplicationInfoInSources().absolutePath)
        arg(value: "$communityHome/native/WinLauncher/WinLauncher/resource.h")
        arg(value: launcherPropertiesPath)
        arg(value: outputPath)
        classpath {
          pathelement(location: "$communityHome/build/lib/launcher-generator.jar")
          fileset(dir: "$communityHome/lib") {
            include(name: "guava*.jar")
            include(name: "jdom.jar")
            include(name: "sanselan*.jar")
          }
          resourceModules.collectMany { it.sourceRoots }.each { JpsModuleSourceRoot root ->
            pathelement(location: root.file.absolutePath)
          }
          buildContext.productProperties.brandingResourcePaths.each {
            pathelement(location: it)
          }
        }
      }
    }
  }

  private void buildWinZip(String jreDirectoryPath, String zipNameSuffix) {
    buildContext.messages.block("Build Windows ${zipNameSuffix}.zip distribution") {
      def targetPath = "$buildContext.paths.artifacts/${buildContext.productProperties.baseArtifactName(buildContext.applicationInfo, buildContext.buildNumber)}${zipNameSuffix}.zip"
      def zipPrefix = customizer.rootDirectoryName(buildContext.applicationInfo, buildContext.buildNumber)
      def dirs = [buildContext.paths.distAll, winDistPath]
      if (jreDirectoryPath != null) {
        dirs += jreDirectoryPath
      }
      buildContext.messages.progress("Building Windows ${zipNameSuffix}.zip archive")
      buildContext.ant.zip(zipfile: targetPath) {
        dirs.each {
          zipfileset(dir: it, prefix: zipPrefix)
        }
      }
      buildContext.notifyArtifactBuilt(targetPath)
    }
  }
}