package com.mcwa.oracleformsgradleplugin

import groovy.io.FileType
import org.apache.commons.io.FilenameUtils
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FormsCompilePlugin implements Plugin<Project> {

    static def findExecutable(foldersToCheck, executableFilename){
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

    static def getSchemaForFilename(String path){
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
        def ext = project.extensions.create('oracleForms', FormsCompilePluginExtension)

        //setup default build folders
        ext.buildSourceSubdir = ext.buildSourceSubdir ?: new File(project.buildDir, "oracleforms")
        ext.buildOutputSubdir = ext.buildOutputSubdir ?: new File(project.buildDir, "output")
        ext.buildXmlSubdir = ext.buildXmlSubdir ?: new File(project.buildDir, "xml")
        ext.buildLogSubdir = ext.buildLogSubdir ?: new File(project.buildDir, "logs")

        project.task('build'){
            group 'Forms Compile (12c)'
            description 'Runs all tasks necessary for a full build'
            dependsOn 'copySourceForBuild', 'compileForms', 'collectCompiledFiles', 'collectLogFiles'
        }

        project.task('generateXml'){
            group 'Forms Compile (12c)'
            description 'Converts .fmb files to .xml'
            dependsOn 'copySourceForBuild', 'convertFormToXml', 'collectXmlFiles'
        }

        project.task('clean', type: Delete){
            group 'Forms Compile (12c)'
            description 'Cleans up the project'

            delete ext.buildSourceSubdir
            delete ext.buildOutputSubdir
            delete ext.buildXmlSubdir
            delete ext.buildLogSubdir
        }

        project.task('copySourceForBuild', type: Copy){
            group 'Forms Compile (12c)'
            description 'Copy all source files into build directory'

            from(new File(project.projectDir, "/src/main/").listFiles()) {
                include "**/*.cfg"
                ext.fileTypes.each {
                    include("**/*.${it.sourceFileExtension}")
                }
            }
            into ext.buildSourceSubdir
        }

        project.task('collectCompiledFiles', type:Copy){
            group 'Forms Compile (12c)'
            description 'Copy all compiled files into output directory'
            dependsOn 'compileForms'
            shouldRunAfter 'compileForms'

            from(ext.buildSourceSubdir) {
                ext.fileTypes.each {
                    include("**/*.${it.binaryFileExtension}")
                }
            }
            into ext.buildOutputSubdir

            eachFile { FileCopyDetails fcd ->
                fcd.path = fcd.path..replaceAll("(?i)/forms/", "/exe/")
            }
        }

        project.task('collectXmlFiles', type:Copy){
            group 'Forms Compile (12c)'
            description 'Copy all xml files into xml directory'
            dependsOn 'convertFormToXml'
            shouldRunAfter 'convertFormToXml'

            from(ext.buildSourceSubdir) {
                ext.fileTypes.each {
                    include("**/*.xml")
                }
            }
            into ext.buildXmlSubdir
        }

        project.task('collectLogFiles', type:Copy){
            group 'Forms Compile (12c)'
            description 'Copy all compiler log files into output directory'
            dependsOn 'compileForms'
            shouldRunAfter 'compileForms'

            from(ext.buildSourceSubdir) {
                ext.fileTypes.each {
                    include("**/*.err")
                }
            }
            into ext.buildLogSubdir
        }

        project.task('convertFormToXml'){
            group 'Forms Compile (12c)'
            description 'Converts all files to xml format'
            dependsOn 'copySourceForBuild'

            doLast {
                //if they didn't explicitly set compiler path, search for it
                if(ext.xmlConverterPath == null || ext.xmlConverterPath.isEmpty()){
                    ext.xmlConverterPath = findExecutable(
                            ext.foldersToSearchForCompiler.findAll{it != null && it != "null"}, //exclude any nulls
                            ext.xmlConverterFileName)
                }

                if(ext.xmlConverterPath == null){
                    project.logger.error "Unable to find a converter! Please specify the path explicitly."
                    throw new Exception("No Converter Found")
                } else {
                    project.logger.quiet("Using converter: '${ext.xmlConverterPath}'")
                }

                def pool = Executors.newFixedThreadPool(ext.maxCompilerThreads)

                project.fileTree(ext.buildSourceSubdir).matching{include "**/*.fmb"}.each { File f ->
                    pool.execute {
                        def modulePath = f.getAbsolutePath()
                        def command = """${ext.xmlConverterPath} "$modulePath" OVERWRITE=YES"""
                        project.logger.quiet "converting $modulePath to xml"
                        project.logger.debug(command)
                        def proc = command.execute([], f.getParentFile())
                        proc.waitForOrKill(ext.compilerTimeoutMs)
                    }
                }

                project.logger.trace("All jobs submitted to pool, awaiting shutdown...")
                pool.shutdown()
                pool.awaitTermination(ext.taskTimeoutMinutes, TimeUnit.MINUTES)
                project.logger.trace("Job pool terminated.")
            }
        }

        project.task('compileForms'){
            group 'Forms Compile (12c)'
            description 'Compiles all Oracle Forms files in the build directory'
            dependsOn 'copySourceForBuild'

            doLast {
                //if they didn't explicitly set compiler path, search for it
                if(ext.compilerPath == null || ext.compilerPath.isEmpty()){
                    ext.compilerPath = findExecutable(
                            ext.foldersToSearchForCompiler
                                    .findAll{it != null && it != "null"}, //exclude any nulls
                            ext.compilerFileName)
                }

                if(ext.compilerPath == null){
                    project.logger.error "Unable to find a compiler! Please specify the path explicitly."
                    throw new Exception("No Compiler Found")
                } else {
                    project.logger.quiet("Using compiler: '${ext.compilerPath}'")
                }

                //load compile config
                Properties compileProps = new Properties()
                try {
                    ext.compileConfigFile.withInputStream {
                        compileProps.load(it)
                    }
                } catch (Exception e){
                    project.logger.warn("Unable to load properties file, will try to use environment variables. Error was: ${e.message}")
                }
                def sid = compileProps.sid  ?: System.env.ORACLE_SID

                // we need to keep separate lists of each file we find by type so
                // we can appropriately check its completion by looking at the binary file
                def files = [:]
                ext.fileTypes.each{
                    files[it] = []
                }

                //collect all compileable files
                //TODO: folders to search for compilables should be set in ext object
                def schemaDirs =  project.layout.files { ext.buildSourceSubdir.listFiles() }

                schemaDirs.filter{it.isDirectory()}.each{
                    it.eachFileRecurse(FileType.FILES) { file ->
                        def filename = file.getAbsolutePath()

                        def fileType = ext.fileTypes.find{ it.sourceFileExtension.equalsIgnoreCase(FilenameUtils.getExtension(filename))}

                        if(fileType != null){
                            project.logger.debug("Found file $file, adding to fileType $fileType")
                            files[fileType].add(file)
                        } else {
                            project.logger.debug("Not compiling $file because fileType could not be determined.")
                        }
                    }
                }

                def pool = Executors.newFixedThreadPool(ext.maxCompilerThreads)

                //get all filetypes by compileOrder
                def compileSteps = ext.fileTypes.groupBy{it.compileOrder}

                //compile
                compileSteps.keySet().sort().each{ priority ->
                    project.logger.lifecycle("Compiling types with priority $priority: ${compileSteps[priority]}")

                    compileSteps[priority].each { fileType ->
                        if(fileType.compilationRequired) {
                            project.logger.lifecycle("Compiling type: ${fileType}")
                            files[fileType].each{ File lib ->
                                pool.execute {
                                    def workingDir = lib.getParentFile()
                                    def modulePath = lib.getAbsolutePath()
                                    def moduleName = FilenameUtils.getBaseName(modulePath)
                                    //compiler has no stdout or stderr, instead writes to <module>.err
                                    def compilerLogFile = new File(workingDir, "${moduleName}.err")
                                    def outputFile = new File(workingDir, "${moduleName}.${fileType.binaryFileExtension}")
                                    def username = null
                                    def password = null
                                    if (fileType.logonRequired){
                                        def schema = getSchemaForFilename(modulePath).toLowerCase()
                                        if(schema == null){
                                            project.logger.warn "no schema found for $modulePath"
                                        }
                                        username = compileProps."${schema}User" ?: System.env."${schema}User"
                                        password = compileProps."${schema}Pass" ?: System.env."${schema}Pass"
                                    }

                                    def command = fileType.getCompileCommand(ext.compilerPath, modulePath, username, password, sid)
                                    project.logger.quiet "compiling $modulePath"
                                    project.logger.debug(command)
                                    def proc = command.execute([], workingDir)
                                    proc.waitForOrKill(ext.compilerTimeoutMs)

                                    //output compiler errors as warnings (because some error codes are just warnings)
                                    if(compilerLogFile.exists()){
                                        //grep log file for 'ORA-', 'FRM-', etc.
                                        def errorLines = compilerLogFile.text.tokenize('\n').findAll{ line ->
                                            ext.errorTokens.any{ line.contains(it) }
                                        }
                                        if(!errorLines.isEmpty()){
                                            project.logger.warn("Errors while compiling $modulePath: \n${errorLines.join('\n')}" )
                                        }
                                    }
                                    //check that file compiled correctly
                                    if(!outputFile.exists()) {
                                        //if compile fails without any compiler log, probably a TNS error
                                        throw new GradleException("$modulePath failed to compile! Expected output file was: $outputFile")
                                    }
                                }
                            }
                        } else {
                            project.logger.lifecycle("No compile required for type: ${fileType}")
                        }
                    }
                }

                project.logger.trace("All jobs submitted to pool, awaiting shutdown...")
                pool.shutdown()
                pool.awaitTermination(ext.taskTimeoutMinutes, TimeUnit.MINUTES)
                project.logger.trace("Job pool terminated.")
            }
        }
    }
}
