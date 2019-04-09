package com.mcwa.oracleformsgradleplugin

class OracleFileType {
    //file extensions should NOT include periods (e.g. "fmb", not ".fmb")
    String sourceFileExtension
    String binaryFileExtension
    Integer compileOrder    //order to compile in. lower numbers compiled first.

    Boolean compilationRequired
    Boolean logonRequired
    String moduleType
    //compileAll should almost always be special https://linuxappsdba.blogspot.com/2009/06/why-compile-forms-with.html
    String compileAll = 'special'

    OracleFileType(srcExt, binExt, compOrd, compReq, logonReq, modType){
        this.sourceFileExtension = srcExt
        this.binaryFileExtension = binExt
        this.compileOrder = compOrd
        this.compilationRequired = compReq
        this.logonRequired = logonReq
        this.moduleType = modType
    }

    String toString(){
        return "[ModuleType: $moduleType, srcExt: $sourceFileExtension]"
    }

    String getCompileCommand(String compilerPath, String modulePath, String username, String password, String sid) {
        def command = """$compilerPath module="$modulePath" """

        if(logonRequired){
            command += """ userid=$username/$password@$sid """
        } else {
            command += " logon=no "
        }

        command += """ module_type=$moduleType batch=yes compile_all=$compileAll"""

        return command
    }
}
