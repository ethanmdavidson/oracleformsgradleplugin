package com.mcwa.oracleformsgradleplugin

class FormsCompilePluginExtension {
    List<String> foldersToSearchForCompiler =
            ["$System.env.ORACLE_HOME", //first check oracle path
             "C:/oracle"] //finally check a common oracle installation location

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

    def compilerTimeoutMs = 60*1000	//max time to wait for compile process to finish

    File compileConfigFile = new File("compile.properties")

    def maxCompilerThreads = 8	//number of compiler processes to run concurrently

    def taskTimeoutMinutes	= 60	//number of minutes to wait for all compiler processes
}
