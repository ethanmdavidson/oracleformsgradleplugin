package com.mcwa.oracleformsgradleplugin

class CompileableFileType {
    //file extensions should NOT include periods (e.g. "fmb", not ".fmb")
    String sourceFileExtension
    String binaryFileExtension
    ModuleType moduleType	//which module to compile this filetype as

    CompileableFileType(source, bin, type){
        this.sourceFileExtension = source.toLowerCase()
        this.binaryFileExtension = bin.toLowerCase()
        this.moduleType = type
    }
}
