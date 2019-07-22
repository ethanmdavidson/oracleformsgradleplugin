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
    static final String GROUP_NAME = "Oracle Forms"

    static final String compileFormsTask = 'compileForms'
    static final String convertFormsToXmlTask = 'convertFormsToXml'
    static final String copySourceForBuildTask = 'copySourceForBuild'
    static final String collectCompiledFilesTask = 'collectCompiledFiles'
    static final String collectLogFilesTask = 'collectLogFiles'
    static final String collectXmlFilesTask = 'collectXmlFiles'

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

    static String getSchemaFromModuleCfg(String modulePath){
        File configFile = new File(FilenameUtils.removeExtension(modulePath) + ".cfg")
        if(configFile.exists()){
            Properties properties = new Properties()
            configFile.withInputStream {
                properties.load(it)
            }
            if(properties.compileSchema != null){
                return properties.compileSchema
            }
        }
        return null
    }

    static def getSchemaForFilename(String path){
        //when given a path to a file, this will return the string representing the schema
        // that file should be compiled with.
        //first check if the file has a corresponding config file
        // e.g. cis/forms/wo_assign.fmb config would be cis/forms/wo_assign.cfg
        //config files should put the schema in the compileSchema key
        def schemaFromModule = getSchemaFromModuleCfg(path)
        if(schemaFromModule != null){
            return schemaFromModule
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
            group GROUP_NAME
            description 'Runs all tasks necessary for a full build'
            dependsOn copySourceForBuildTask, compileFormsTask, collectCompiledFilesTask, collectLogFilesTask
        }

        project.task('generateXml'){
            group GROUP_NAME
            description 'Converts .fmb files to .xml'
            dependsOn copySourceForBuildTask, convertFormsToXmlTask, collectXmlFilesTask
        }

        project.task('clean', type: Delete){
            group GROUP_NAME
            description 'Cleans up the project'

            delete ext.buildSourceSubdir
            delete ext.buildOutputSubdir
            delete ext.buildXmlSubdir
            delete ext.buildLogSubdir
        }

        project.task(copySourceForBuildTask, type: Copy){
            group GROUP_NAME
            description 'Copy all source files into build directory'
            caseSensitive false

            from(new File(project.projectDir, "/src/main/").listFiles()) {
                include "**/*.cfg"
                ext.fileTypes.each {
                    include("**/*.${it.sourceFileExtension}")
                }
                ext.additionalFiles.each {
                    include(it)
                }
            }
            into ext.buildSourceSubdir

            //this code is to preserve the timestamps (https://github.com/gradle/gradle/issues/1252)
            List<FileCopyDetails> copyDetails = []
            eachFile { FileCopyDetails fcd -> copyDetails << fcd }
            doLast {
                copyDetails.each { FileCopyDetails details ->
                    def target = new File(ext.buildSourceSubdir, details.path)
                    if(target.exists()) { target.setLastModified(details.lastModified) }
                }
            }
        }

        project.task(collectCompiledFilesTask, type:Copy){
            group GROUP_NAME
            description 'Copy all compiled files into output directory'
            dependsOn compileFormsTask
            shouldRunAfter compileFormsTask
            caseSensitive false

            from(ext.buildSourceSubdir) {
                ext.fileTypes.each {
                    include("**/*.${it.binaryFileExtension}")
                }
                ext.additionalFiles.each {
                    include(it)
                }
            }
            into ext.buildOutputSubdir

            eachFile { FileCopyDetails fcd ->
                fcd.path = fcd.path.replaceAll("(?i)/forms/", "/exe/")
            }
        }

        project.task(collectXmlFilesTask, type:Copy){
            group GROUP_NAME
            description 'Copy all xml files into xml directory'
            dependsOn convertFormsToXmlTask
            shouldRunAfter convertFormsToXmlTask
            caseSensitive false

            from(ext.buildSourceSubdir) {
                ext.fileTypes.each {
                    include("**/*.xml")
                }
            }
            into ext.buildXmlSubdir
        }

        project.task(collectLogFilesTask, type:Copy){
            group GROUP_NAME
            description 'Copy all compiler log files into output directory'
            dependsOn compileFormsTask
            shouldRunAfter compileFormsTask
            caseSensitive false

            from(ext.buildSourceSubdir) {
                ext.fileTypes.each {
                    include("**/*.err")
                }
            }
            into ext.buildLogSubdir
        }

        project.task(convertFormsToXmlTask){
            group GROUP_NAME
            description 'Converts all files to xml format'
            dependsOn copySourceForBuildTask

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

                project.fileTree(ext.buildSourceSubdir).matching{include "**/*.fmb"}.each { File f ->
                    def modulePath = f.getAbsolutePath()
                    def command = """${ext.xmlConverterPath} "$modulePath" OVERWRITE=YES"""
                    project.logger.quiet "converting $modulePath to xml"
                    project.logger.debug(command)
                    def proc = command.execute([], f.getParentFile())
                    proc.waitForOrKill(ext.compilerTimeoutMs)
                }
                project.logger.debug("All modules converted.")
            }
        }

        project.task(compileFormsTask){
            group GROUP_NAME
            description 'Compiles all Oracle Forms files in the build directory'
            dependsOn copySourceForBuildTask

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

                //Add any folders with compiled files to the FORMS_PATH
                def pathFolders = [] as Set

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
                            pathFolders += file.getParentFile().getAbsolutePath()
                        } else {
                            project.logger.debug("Not compiling $file because fileType could not be determined.")
                        }
                    }
                }

                def formsPath = pathFolders.join(File.pathSeparator)

                //set up environment variables for compiler
                def envVars = []

                //prepend any existing FORMS_PATH
                String sysFormsPath = System.env.FORMS_PATH
                if(sysFormsPath != null && !sysFormsPath.isEmpty()){
                    if(!sysFormsPath.endsWith(File.pathSeparator)){
                        sysFormsPath += File.pathSeparator
                    }
                    formsPath = sysFormsPath + formsPath
                }
                project.logger.debug("FORMS_PATH is: $formsPath")
                envVars.add("FORMS_PATH=$formsPath")
                //6i uses a different variable https://gph.is/1fFAj2t
                //it doesn't hurt the compiler to have both set, but it sure hurts me
                envVars.add("FORMS60_PATH=$formsPath")

                //pass through TNS_ADMIN, if it exists
                String tnsAdminPath = System.env.TNS_ADMIN
                project.logger.debug("TNS_ADMIN is: $tnsAdminPath")
                if (!tnsAdminPath?.isEmpty()){
                    envVars.add("TNS_ADMIN=$tnsAdminPath")
                }

                //get all filetypes by compileOrder
                def compileSteps = ext.fileTypes.groupBy{it.compileOrder}

                //compile
                compileSteps.keySet().sort().each{ priority ->
                    project.logger.lifecycle("Compiling types with priority $priority: ${compileSteps[priority]}")

                    compileSteps[priority].each { fileType ->
                        if(fileType.compilationRequired) {
                            project.logger.lifecycle("Compiling type: ${fileType}")
                            files[fileType].each{ File module ->
                                def workingDir = module.getParentFile()
                                def modulePath = module.getAbsolutePath()
                                def moduleName = FilenameUtils.getBaseName(modulePath)
                                //compiler has no stdout or stderr, instead writes to <module>.err
                                def compilerLogFile = new File(workingDir, "${module.getName()}.err")
                                def outputFile = new File(workingDir, "${moduleName}.${fileType.binaryFileExtension}")

                                //if executable is up-to-date, skip compilation
                                if(outputFile.exists() && outputFile.lastModified() > module.lastModified()){
                                    project.logger.quiet("${modulePath} is up to date, skipping.")
                                    return
                                }

                                def username = null
                                def password = null
                                if (fileType.logonRequired){
                                    def schema = getSchemaForFilename(modulePath).toLowerCase()
                                    if(schema == null){
                                        project.logger.warn "no schema found for $modulePath"
                                    }
                                    username = compileProps."${schema}User" ?: System.env."${schema}User"
                                    password = compileProps."${schema}Pass" ?: System.env."${schema}Pass"
                                } else {
                                    //even if logonRequired=false, logon if a <module>.cfg file specifies the schema
                                    def schemaFromModule = getSchemaFromModuleCfg(modulePath)
                                    if(schemaFromModule != null){
                                        username = compileProps."${schemaFromModule}User" ?: System.env."${schemaFromModule}User"
                                        password = compileProps."${schemaFromModule}Pass" ?: System.env."${schemaFromModule}Pass"
                                    }
                                }

                                def command = fileType.getCompileCommand(ext.compilerPath, modulePath, username, password, sid)
                                project.logger.quiet "compiling $modulePath as user $username"
                                project.logger.debug(command)
                                def proc = command.execute(envVars, workingDir)
                                proc.waitForOrKill(ext.compilerTimeoutMs)

                                //output compiler errors as warnings (because some error codes are just warnings)
                                if(compilerLogFile.exists()){
                                    //grep log file for 'ORA-', 'FRM-', etc.
                                    def errorLines = compilerLogFile.text.tokenize('\n').findAll{ line ->
                                        ext.errorTokens.any{ line.contains(it) }
                                    }
                                    if(!errorLines.isEmpty()){
                                        project.logger.debug("Error file is: ${compilerLogFile.getAbsolutePath()}")
                                        project.logger.warn("Errors while compiling $modulePath: \n${errorLines.join('\n')}" )
                                    }
                                }

                                //check that file compiled correctly
                                if(!outputFile.exists() || outputFile.lastModified() < module.lastModified()) {
                                    //if compile fails without any compiler log, probably a TNS error
                                    throw new GradleException("$modulePath failed to compile! Expected output file was: $outputFile")
                                }
                            }
                        } else {
                            project.logger.lifecycle("No compile required for type: ${fileType}")
                        }
                    }
                }
                project.logger.debug("All modules compiled.")
            }
        }
    }
}
