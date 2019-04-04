package com.mcwa.oracleformsgradleplugin

import groovy.io.FileType
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FormsCompilePlugin implements Plugin<Project> {
    def findExecutable(foldersToCheck, executableFilename){
        def executable = null
        for(String filename : foldersToCheck){
            //eachFileRecurse seems to be the best way to search for files, but
            // the code is pretty ugly (can't return from inside the closure)
            new File(filename).eachFileRecurse(FileType.FILES) {
                if(executable == null && it.getName().equalsIgnoreCase(executableFilename)){
                    executable = it
                }
            }
            if(executable != null){
                return executable
            }
        }
        return executable
    }

    def getSchemaForFilename(String path){
        //when given a path to a file, this will return the string representing the schema
        // that file should be compiled with.
        //first check if the file has a corresponding config file
        // e.g. cis/forms/wo_assign.fmb config would be cis/forms/wo_assign.cfg
        //config files should put the schema in the compileSchema key
        File configFile = new File(FilenameUtils.removeExtension(path) + ".cfg")
        if(configFile.exists()){
            Properties properties = new Properties()
            configFile.withInputStream {
                properties.load(it)
            }
            if(properties.compileSchema != null){
                return properties.compileSchema
            }
        }

        //if no config specified for this file, check the root config
        //e.g. cis/forms/wo_assign.fmb root config would be cis/root.cfg
        File rootCfgFile = new File(new File(path)?.getParentFile()?.getParentFile(), "root.cfg")
        if(rootCfgFile.exists()){
            Properties properties = new Properties()
            rootCfgFile.withInputStream {
                properties.load(it)
            }
            if(properties.compileSchema != null){
                return properties.compileSchema
            }
        }

        //if no config file, infer it from the path
        // e.g. cis/forms/wo_assign.fmb would be 'cis'
        return new File(path)?.getParentFile()?.getParentFile()?.getName()
    }

    void apply(Project project){
        def extension = project.extensions.create('oracleForms', FormsCompilePluginExtension)

        project.task('build'){
            group 'Forms Compile (12c)'
            description 'Runs all tasks necessary for a full build'
            dependsOn 'copySourceForBuild', 'compileForms', 'copyExecutablesToOutput'
        }

        project.task('generateXml'){
            group 'Forms Compile (12c)'
            description 'Converts .fmb files to .xml'
            dependsOn 'copySourceForBuild', 'convertFormToXml', 'collectXmlFiles'
        }

        project.task('clean', type: Delete){
            group 'Forms Compile (12c)'
            description 'Cleans up the project'

            delete "${project.projectDir}/build"
            delete project.fileTree(project.projectDir){
                //the forms compiler copies pll files into project root for some reason
                include '*.pll'
                include 'sqlnet.log'
            }
        }

        project.task('copySourceForBuild', type: Copy){
            group 'Forms Compile (12c)'
            description 'Copy all source files into build directory'

            from(new File(project.projectDir, "/src/main/").listFiles()) {
                extension.fileTypes.each {
                    include("**/*.${it.sourceFileExtension}")
                }
            }
            into project.buildDir
        }

        project.task('copyExecutablesToOutput', type:Copy){
            group 'Forms Compile (12c)'
            description 'Copy all compiled files into output directory'
            dependsOn 'compileForms'
            shouldRunAfter 'compileForms'

            from(project.buildDir) {
                extension.fileTypes.each {
                    include("**/*.${it.binaryFileExtension}")
                }
            }
            into "${project.buildDir}/output/"
        }

        project.task('collectXmlFiles', type:Copy){
            group 'Forms Compile (12c)'
            description 'Copy all xml files into xml directory'
            dependsOn 'convertFormToXml'
            shouldRunAfter 'convertFormToXml'

            from(project.buildDir) {
                extension.fileTypes.each {
                    include("**/*.xml")
                }
            }
            into "${project.buildDir}/xml/"
        }

        project.task('convertFormToXml'){
            group 'Forms Compile (12c)'
            description 'Converts all files to xml format'
            dependsOn 'copySourceForBuild'

            doLast {
                //if they didn't explicitly set compiler path, search for it
                if(extension.xmlConverterPath == null || extension.xmlConverterPath.isEmpty()){
                    extension.xmlConverterPath = findExecutable(
                            extension.foldersToSearchForCompiler
                                    .findAll{it != null && it != "null"}, //exclude any nulls
                            extension.xmlConverterFileName)
                }

                if(extension.xmlConverterPath == null){
                    project.logger.error "Unable to find a converter! Please specify the path explicitly."
                    throw new Exception("No Converter Found")
                } else {
                    project.logger.quiet("Using converter: '${extension.xmlConverterPath}'")
                }

                project.fileTree(project.buildDir).matching{include "**/*.fmb"}.each { File f ->
                    project.exec {
                        workingDir f.getParentFile()
                        commandLine extension.xmlConverterPath, f.getAbsolutePath(), 'OVERWRITE=YES'
                    }
                }
            }
        }

        project.task('compileForms'){
            group 'Forms Compile (12c)'
            description 'Compiles all Oracle Forms files in the build directory'
            dependsOn 'copySourceForBuild'

            doLast {
                //if they didn't explicitly set compiler path, search for it
                if(extension.compilerPath == null || extension.compilerPath.isEmpty()){
                    extension.compilerPath = findExecutable(
                            extension.foldersToSearchForCompiler
                                    .findAll{it != null && it != "null"}, //exclude any nulls
                            extension.compilerFileName)
                }

                if(extension.compilerPath == null){
                    project.logger.error "Unable to find a compiler! Please specify the path explicitly."
                    throw new Exception("No Compiler Found")
                } else {
                    project.logger.quiet("Using compiler: '${extension.compilerPath}'")
                }

                //load compile config
                Properties compileProps = new Properties()
                try {
                    extension.compileConfigFile.withInputStream {
                        compileProps.load(it)
                    }
                } catch (Exception e){
                    project.logger.warn("Unable to load properties file, will try to use environment variables. Error was: ${e.message}")
                }
                def sid = compileProps.sid  ?: System.env.ORACLE_SID

                //TODO: refactor this bit to work dynamically with multiple compileableTypes
                // moduleTypes should be in an enum, and should define order
                // but we need to keep separate lists of each file we find by type so
                // we can appropriately check its completion by looking at the binary file
                def files = [:]
                extension.fileTypes.each{
                    files[it] = []
                }

                //collect all libraries, menus, forms
                //TODO: folders to search for compilables should be set in extension object
                def schemaDirs = project.layout.files { project.buildDir.listFiles() }

                schemaDirs.filter{it.isDirectory()}.each{
                    it.eachFileRecurse(FileType.FILES) { file ->
                        def filename = file.getAbsolutePath()

                        def fileType = extension.fileTypes.find{ it.sourceFileExtension.equalsIgnoreCase(FilenameUtils.getExtension(filename))}

                        if(fileType != null){
                            files[fileType].add(file)
                        }
                    }
                }

                def pool = Executors.newFixedThreadPool(extension.maxCompilerThreads)

                //compile all libraries
                project.logger.lifecycle "Compiling Libraries"
                files.each{ filetype, fileList ->
                    if(filetype.moduleType == ModuleType.LIBRARY){
                        fileList.each{ lib ->
                            pool.execute {
                                def modulePath = lib.getAbsolutePath()
                                def command = "${extension.compilerPath} module=\"$modulePath\" logon=no module_type=library batch=yes compile_all=special"
                                project.logger.quiet "compiling $modulePath"
                                def proc = command.execute()
                                proc.waitForOrKill(extension.compilerTimeoutMs)
                            }
                        }
                    }
                }

                //compile all menus
                project.logger.lifecycle "Compiling Menus"
                files.each{ filetype, fileList ->
                    if(filetype.moduleType == ModuleType.MENU){
                        fileList.each{ menu ->
                            pool.execute {
                                def modulePath = menu.getAbsolutePath()
                                def schema = getSchemaForFilename(modulePath).toLowerCase()
                                if(schema == null){
                                    project.logger.warn "no schema found for $modulePath"
                                }
                                def user = compileProps."${schema}User" ?: System.env."${schema}User"
                                def pass = compileProps."${schema}Pass" ?: System.env."${schema}Pass"
                                def command = "${extension.compilerPath} module=\"$modulePath\" userid=$user/$pass@$sid module_type=menu batch=yes compile_all=special"
                                project.logger.quiet "compiling $modulePath as $schema"
                                def proc = command.execute()
                                proc.waitForOrKill(extension.compilerTimeoutMs)
                            }
                        }
                    }
                }

                //compile all forms
                project.logger.lifecycle "Compiling Forms"
                files.each{ filetype, fileList ->
                    if(filetype.moduleType == ModuleType.FORM){
                        fileList.each { form ->
                            pool.execute {
                                def modulePath = form.getAbsolutePath()
                                def schema = getSchemaForFilename(modulePath).toLowerCase()
                                if(schema == null){
                                    project.logger.warn "no schema found for $modulePath"
                                }
                                def user = compileProps."${schema}User" ?: System.env."${schema}User"
                                def pass = compileProps."${schema}Pass" ?: System.env."${schema}Pass"
                                def command = "${extension.compilerPath} module=\"$modulePath\" userid=$user/$pass@$sid module_type=form batch=yes compile_all=special"
                                project.logger.quiet "compiling $modulePath as $schema"
                                def proc = command.execute()
                                proc.waitForOrKill(extension.compilerTimeoutMs)
                            }
                        }
                    }
                }

                pool.shutdown()
                pool.awaitTermination(extension.taskTimeoutMinutes, TimeUnit.MINUTES)
            }
        }
    }
}
