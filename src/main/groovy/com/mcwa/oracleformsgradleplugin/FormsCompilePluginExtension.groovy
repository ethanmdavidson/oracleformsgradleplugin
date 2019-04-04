package com.mcwa.oracleformsgradleplugin

class FormsCompilePluginExtension {
    List<String> foldersToSearchForCompiler =
            ["$System.env.ORACLE_HOME", //first check oracle path
             "C:/oracle"] //finally check a common oracle installation location

    String compilerFileName = "frmcmp.exe"	//filename to search for

    String compilerPath = null	//if this is set explicitly, no search will be performed

    List<CompileableFileType> fileTypes = [
            new CompileableFileType("pll", "plx", ModuleType.LIBRARY),
            new CompileableFileType("mmb", "mmx", ModuleType.MENU),
            new CompileableFileType("fmb", "fmx", ModuleType.FORM)]

    def compilerTimeoutMs = 60*1000	//max time to wait for compile process to finish

    File compileConfigFile = new File("compile.properties")

    def maxCompilerThreads = 8	//number of compiler processes to run concurrently

    def taskTimeoutMinutes	= 60	//number of minutes to wait for all compiler processes
}
