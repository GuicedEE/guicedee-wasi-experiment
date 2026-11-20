package org.teavm.classlib.java.io;

import java.io.IOException;
import java.io.Writer;

public abstract class TFilterWriter extends Writer {
    protected Writer out;
    protected TFilterWriter(Writer out) { super(out); this.out = out; }
    @Override public void write(int c) throws IOException { out.write(c); }
    @Override public void write(char[] c, int off, int len) throws IOException { out.write(c, off, len); }
    @Override public void write(String s, int off, int len) throws IOException { out.write(s, off, len); }
    @Override public void flush() throws IOException { out.flush(); }
    @Override public void close() throws IOException { out.close(); }
}
