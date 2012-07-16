package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

/**
 * @author Andrey Pavlenko
 */
public interface FastimportCommand {
    
    public void write(PrintStream s) throws IOException;
}
