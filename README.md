# OracleFormsGradlePlugin

A Gradle Plugin for working with oracle forms

## How to Use

This plugin requires that Oracle Forms Builder is installed.
It should automatically find the executables it needs by searching 
the folder specified by the `ORACLE_HOME` environment variable.

To add it to a project, simply apply the plugin in typical gradle style:

```groovy
plugins {
    id 'com.mcwa.oracleforms' version '1.8.2'
}
```

Then run the 

## Restrictions

This plugin currently only works with 12c forms, and only 
compiles the following filetypes:

- `.pll`
- `.mmb`
- `.fmb`

`.rdf` files are not compiled because this is not required in 12c.

Source files are expected to be in `/src/main/`, and compiled files will 
end up in `/build/output/`.