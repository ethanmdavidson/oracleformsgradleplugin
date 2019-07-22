package com.mcwa.oracleformsgradleplugin

class FormsCompilePluginExtension {
    List<String> foldersToSearchForCompiler =
            ["$System.env.ORACLE_HOME", //first check oracle path
             "C:/oracle", "C:/orant"] //finally check some common oracle installation locations

    String compilerFileName = "frmcmp.exe"	//filename to search for

    String compilerPath = null	//if this is set explicitly, no search will be performed

    String xmlConverterFileName = "frmf2xml.bat"

    String xmlConverterPath = null //if this is set explicitly, no search will be performed

    List<OracleFileType> fileTypes = [
            new OracleFileType("pll", "plx", 0, true, false, 'library'),
            new OracleFileType("olb", "olb", 0, false, false, 'olb'),
            new OracleFileType("mmb", "mmx", 1, true, true, 'menu'),
            new OracleFileType("fmb", "fmx", 2, true, true, 'form'),
            new OracleFileType('rdf', 'rdf', 2, false, false, 'report') ]

    //additional files which aren't compiled, but are used by the forms and need to be deployed with them
    //e.g. images which are referenced by the form
    //These should be specified in the ant glob pattern used by gradle (e.g. **/*.tif for all tif files)
    //https://docs.gradle.org/current/userguide/working_with_files.html
    List<String> additionalFiles = ["**/*.tif"]

    def compilerTimeoutMs = 5*60*1000	//max time to wait for compile process to finish

    File compileConfigFile = new File("compile.properties")

    File buildSourceSubdir = null

    File buildOutputSubdir = null

    File buildXmlSubdir = null

    File buildLogSubdir = null

    //tokens to grep for in compiler logs
    List<String> errorTokens = ["FRM-", "ORA-", "TNS-", "PL/SQL ERROR", "PDE-"]
}
