package com.mcwa.oracleformsgradleplugin

enum ModuleType {
    LIBRARY('library', false),
    MENU('menu', true),
    FORM('form', true)

    final String moduleTypeArg	//the arg to pass to the compiler for this module
    final boolean requiresLogon	//does this module require logon?

    private ModuleType(String arg, boolean logon){
        this.moduleTypeArg = arg
        this.requiresLogon = logon
    }
}