/* kXML 2
 *
 * Copyright (C) 2000, 2001, 2002 
 *               Stefan Haustein
 *               D-46045 Oberhausen (Rhld.),
 *               Germany. All Rights Reserved.
 *
 * The contents of this file are subject to the "Lesser GNU Public
 * License" (LGPL); you may not use this file except in compliance
 * with the License.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific terms governing rights and limitations
 * under the License.
 *
 * Thanks to Paul Palaszewski, Wilhelm Fitzpatrick, 
 * Eric Foster-Johnson, Michael Angel, and Liam Quinn for providing various
 * fixes and hints for the KXML 1 parser.
 * */

package org.kxml2.io;

import java.io.*;
import java.util.*;

import org.xmlpull.v1.*;

/** A simple, pull based XML parser. This classe replaces the
    XmlParser class and the corresponding event classes. */

public class KXmlParser implements XmlPullParser {

    static final private String UNEXPECTED_EOF =
        "Unexpected EOF";
    static final private String ILLEGAL_TYPE =
        "Wrong event type";
    static final private int LEGACY = 999;

    // general

 //   private boolean reportNspAttr;
    private boolean processNsp;
    private boolean relaxed;
    private Hashtable entityMap;
    private int depth;
    private String[] elementStack = new String[16];
    private String[] nspStack = new String[8];
    private int[] nspCounts = new int[4];

    // source

    private Reader reader;
    private String encoding;
    private char[] srcBuf;

    private int srcPos;
    private int srcCount;

    //    private boolean eof;

    private int line;
    private int column;

    // txtbuffer

    private char[] txtBuf = new char[128];
    private int txtPos;

    // Event-related

    private int type;
    private String text;
    private boolean isWhitespace;
    private String namespace;
    private String prefix;
    private String name;

    private boolean degenerated;
    private int attributeCount;
    private String[] attributes = new String[16];

    /** 
     * A separate peek buffer seems simpler than managing
     * wrap around in the first level read buffer */

    private int[] peek = new int[2];
    private int peekCount;
    private boolean wasCR;

    private boolean unresolved;
    private boolean token;

    public KXmlParser() {
        srcBuf =
            new char[Runtime.getRuntime().freeMemory()
                >= 1048576
                ? 8192
                : 128];
    }

    private final boolean adjustNsp()
        throws XmlPullParserException {

        boolean any = false;

        for (int i = 0; i < attributeCount << 2; i += 4) {
            // * 4 - 4; i >= 0; i -= 4) {

            String attrName = attributes[i + 2];
            int cut = attrName.indexOf(':');
            String prefix;

            if (cut != -1) {
                prefix = attrName.substring(0, cut);
                attrName = attrName.substring(cut + 1);
            }
            else if (attrName.equals("xmlns")) {
                prefix = attrName;
                attrName = null;
            }
            else
                continue;

            if (!prefix.equals("xmlns")) {
                any = true;
            }
            else {
                int j = (nspCounts[depth]++) << 1;

                nspStack = ensureCapacity(nspStack, j + 2);
                nspStack[j] = attrName;
                nspStack[j + 1] = attributes[i + 3];

                if (attrName != null
                    && attributes[i + 3].equals(""))
                    exception("illegal empty namespace");

                //  prefixMap = new PrefixMap (prefixMap, attrName, attr.getValue ());

                //System.out.println (prefixMap);

                    System.arraycopy(
                        attributes,
                        i + 4,
                        attributes,
                        i,
                        ((--attributeCount) << 2) - i);

                    i -= 4;
            }
        }

        if (any) {
            for (int i = (attributeCount << 2) - 4;
                i >= 0;
                i -= 4) {

                String attrName = attributes[i + 2];
                int cut = attrName.indexOf(':');

                if (cut == 0 && !relaxed)
                    throw new RuntimeException(
                        "illegal attribute name: "
                            + attrName
                            + " at "
                            + this);

                else if (cut != -1) {
                    String attrPrefix =
                        attrName.substring(0, cut);

                    attrName = attrName.substring(cut + 1);

                    String attrNs = getNamespace(attrPrefix);

                    if (attrNs == null && !relaxed)
                        throw new RuntimeException(
                            "Undefined Prefix: "
                                + attrPrefix
                                + " in "
                                + this);

                    attributes[i] = attrNs;
                    attributes[i + 1] = attrPrefix;
                    attributes[i + 2] = attrName;

					if (!relaxed) {
                    for (int j = (attributeCount << 2) - 4;
                        j > i;
                        j -= 4)
                        if (attrName.equals(attributes[j + 2])
                            && attrNs.equals(attributes[j]))
                            exception(
                                "Duplicate Attribute: {"
                                    + attrNs
                                    + "}"
                                    + attrName);
					}
                }
            }
        }

        int cut = name.indexOf(':');

        if (cut == 0 && !relaxed)
            exception("illegal tag name: " + name);
        else if (cut != -1) {
            prefix = name.substring(0, cut);
            name = name.substring(cut + 1);
        }

        this.namespace = getNamespace(prefix);

        if (this.namespace == null) {
            if (prefix != null && !relaxed)
                exception("undefined prefix: " + prefix);
            this.namespace = NO_NAMESPACE;
        }

        return any;
    }

    private final String[] ensureCapacity(
        String[] arr,
        int required) {
        if (arr.length >= required)
            return arr;
        String[] bigger = new String[required + 16];
        System.arraycopy(arr, 0, bigger, 0, arr.length);
        return bigger;
    }

    private final void exception(String desc)
        throws XmlPullParserException {
        throw new XmlPullParserException(desc, this, null);
    }

    /** 
     * common base for next and nextToken. Clears the state, except from 
     * txtPos and whitespace. Does not set the type variable */

    private final void nextImpl()
        throws IOException, XmlPullParserException {

        if (reader == null)
            exception("No Input specified");

        if (type == END_TAG)
            depth--;

        attributeCount = -1;

        if (degenerated) {
            degenerated = false;
            type = END_TAG;
            return;
        }

        prefix = null;
        name = null;
        namespace = null;
        text = null;

        type = peekType();

        switch (type) {

            case ENTITY_REF :
                pushEntity();
                break;

            case START_TAG :
                parseStartTag();
                break;

            case END_TAG :
                parseEndTag();
                break;

            case END_DOCUMENT :
                break;

            case TEXT :
                pushText('<', !token);
                if (depth == 0) {
                    if (isWhitespace)
                        type = IGNORABLE_WHITESPACE;
                    // make exception switchable for instances.chg... !!!!
                    //	else 
                    //    exception ("text '"+getText ()+"' not allowed outside root element");
                }
                break;

            default :
                type = parseLegacy(token);
        }
    }

    private final int parseLegacy(boolean push)
        throws IOException, XmlPullParserException {

        String req = "";
        int term;
        int result;

        read(); // <
        int c = read();

        if (c == '?') {
            term = '?';
            result = PROCESSING_INSTRUCTION;
        }
        else if (c == '!') {
            if (peek(0) == '-') {
                result = COMMENT;
                req = "--";
                term = '-';
            }
            else if (peek(0) == '[') {
                result = CDSECT;
                req = "[CDATA[";
                term = ']';
                push = true;
            }
            else {
                result = DOCDECL;
                req = "DOCTYPE";
                term = -1;
            }
        }
        else {
            exception("illegal: <" + c);
            return -1;
        }

        for (int i = 0; i < req.length(); i++)
            read(req.charAt(i));

        if (result == DOCDECL)
            parseDoctype(push);
        else {
            while (true) {
                c = read();
                if (c == -1)
                    exception(UNEXPECTED_EOF);

                if (push)
                    push(c);

                if ((term == '?' || c == term)
                    && peek(0) == term
                    && peek(1) == '>')
                    break;
            }
            read();
            read();

            if (push && term != '?')
                txtPos--;

        }
        return result;
    }

    /** precondition: &lt! consumed */

    private final void parseDoctype(boolean push)
        throws IOException, XmlPullParserException {

        int nesting = 1;
        boolean quoted = false;

        while (true) {
            int i = read();
            switch (i) {

                case -1 :
                    exception(UNEXPECTED_EOF);

                case '\'' :
                    quoted = !quoted;
                    break;

                case '<' :
                    if (!quoted)
                        nesting++;
                    break;

                case '>' :
                    if (!quoted) {
                        if ((--nesting) == 0)
                            return;
                    }
                    break;
            }
            if (push)
                push(i);
        }
    }

    /* precondition: &lt;/ consumed */

    private final void parseEndTag()
        throws IOException, XmlPullParserException {

        read(); // '<'
        read(); // '/'
        name = readName();
        skip();
        read('>');

        int sp = (depth - 1) << 2;

		if (!relaxed) {

	        if (depth == 0) 
	            exception("element stack empty");
        
	        if (!name.equals(elementStack[sp + 3]))
    	        exception("expected: " + elementStack[sp + 3]);
		}
		else if (depth == 0 || !name.equalsIgnoreCase(elementStack[sp + 3]))
				return;
		
		namespace = elementStack[sp];
        prefix = elementStack[sp + 1];
        name = elementStack[sp + 2];
    }

    private final int peekType() throws IOException {
        switch (peek(0)) {
            case -1 :
                return END_DOCUMENT;
            case '&' :
                return ENTITY_REF;
            case '<' :
                switch (peek(1)) {
                    case '/' :
                        return END_TAG;
                    case '?' :
                    case '!' :
                        return LEGACY;
                    default :
                        return START_TAG;
                }
            default :
                return TEXT;
        }
    }

    private final String get(int pos) {
        return new String(txtBuf, pos, txtPos - pos);
    }

    /*
    private final String pop (int pos) {
    String result = new String (txtBuf, pos, txtPos - pos);
    txtPos = pos;
    return result;
    }
    */

    private final void push(int c) {
        if (c == '\r' || c == '\n') {

            if (c == '\n' && wasCR) {
                wasCR = false;
                return;
            }

            wasCR = c == '\r';
            c = type == START_TAG ? ' ' : '\n';
        }
        else
            wasCR = false;

        isWhitespace &= c <= ' ';

        if (txtPos == txtBuf.length) {
            char[] bigger = new char[txtPos * 4 / 3 + 4];
            System.arraycopy(txtBuf, 0, bigger, 0, txtPos);
            txtBuf = bigger;
        }

        txtBuf[txtPos++] = (char) c;
    }

    /** Sets name and attributes */

    private final void parseStartTag()
        throws IOException, XmlPullParserException {

        read(); // <
        name = readName();
        attributeCount = 0;

        while (true) {
            skip();

            int c = peek(0);

            if (c == '/') {
                degenerated = true;
                read();
                skip();
                read('>');
                break;
            }

            if (c == '>') {
                read();
                break;
            }

            if (c == -1)
                exception(UNEXPECTED_EOF);

            String attrName = readName();

            if (attrName.length() == 0)
                exception("attr name expected");

            skip();
            read('=');
            skip();
            int delimiter = read();

            if (delimiter != '\'' && delimiter != '"') {
                if (!relaxed)
                    exception(
                        "<"
                            + name
                            + ">: invalid delimiter: "
                            + (char) delimiter);

                delimiter = ' ';
            }

            int i = (attributeCount++) << 2;

            attributes = ensureCapacity(attributes, i + 4);

            attributes[i++] = "";
            attributes[i++] = null;
            attributes[i++] = attrName;

            int p = txtPos;
            pushText(delimiter, true);

            attributes[i] = get(p);
            txtPos = p;

            if (delimiter != ' ')
                read(); // skip endquote
        }

        int sp = depth++ << 2;

        elementStack = ensureCapacity(elementStack, sp + 4);
        elementStack[sp + 3] = name;

        if (depth >= nspCounts.length) {
            int[] bigger = new int[depth + 4];
            System.arraycopy(
                nspCounts,
                0,
                bigger,
                0,
                nspCounts.length);
            nspCounts = bigger;
        }

        nspCounts[depth] = nspCounts[depth - 1];

        for (int i = attributeCount - 1; i > 0; i--) {
            for (int j = 0; j < i; j++) {
                if (getAttributeName(i)
                    .equals(getAttributeName(j)))
                    exception(
                        "Duplicate Attribute: "
                            + getAttributeName(i));
            }
        }

        if (processNsp)
            adjustNsp();
        else
            namespace = "";

        elementStack[sp] = namespace;
        elementStack[sp + 1] = prefix;
        elementStack[sp + 2] = name;
    }

    /** result: isWhitespace; if the setName parameter is set,
    the name of the entity is stored in "name" */

    private final void pushEntity()
        throws IOException, XmlPullParserException {

        read(); // &

        int pos = txtPos;

        while (true) {
            int c = read();
            if (c == ';')
                break;
            if (c == -1)
                exception(UNEXPECTED_EOF);
            push(c);
        }

        String code = get(pos);
        txtPos = pos;
        if (token && type == ENTITY_REF)
            name = code;

        if (code.charAt(0) == '#') {
            int c =
                (code.charAt(1) == 'x'
                    ? Integer.parseInt(code.substring(2), 16)
                    : Integer.parseInt(code.substring(1)));
            push(c);
            return;
        }

        String result = (String) entityMap.get(code);

        unresolved = result == null;

        if (unresolved) {
            if (!token)
                exception("unresolved: &" + code + ";");
        }
        else {
            for (int i = 0; i < result.length(); i++)
                push(result.charAt(i));
        }
    }

    /** types:
    '<': parse to any token (for nextToken ())
    '"': parse to quote
    ' ': parse to whitespace or '>'
    */

    private final void pushText(
        int delimiter,
        boolean resolveEntities)
        throws IOException, XmlPullParserException {

        int next = peek(0);

        while (next != -1
            && next != delimiter) { // covers eof, '<', '"'

            if (delimiter == ' ')
                if (next <= ' ' || next == '>')
                    break;

            if (next == '&') {
                if (!resolveEntities)
                    break;

                pushEntity();
            }
            else
                push(read());

            next = peek(0);
        }
    }

    private final void read(char c)
        throws IOException, XmlPullParserException {
        int a = read();
        if (a != c)
            exception(
                "expected: '"
                    + c
                    + "' actual: '"
                    + ((char) a)
                    + "'");
    }

    private final int read() throws IOException {
        int result;

        if (peekCount == 0)
            result = peek(0);
        else {
            result = peek[0];
            peek[0] = peek[1];
        }
        //		else {
        //			result = peek[0]; 
        //			System.arraycopy (peek, 1, peek, 0, peekCount-1);
        //		}
        peekCount--;

        column++;

        if (result == '\n') {

            line++;
            column = 1;
        }

        return result;
    }

    /** Does never read more than needed */

    private final int peek(int pos) throws IOException {

        while (pos >= peekCount) {

            int nw;

            if (srcBuf.length <= 1)
                nw = reader.read();
            else if (srcPos < srcCount)
                nw = srcBuf[srcPos++];
            else {
                srcCount = reader.read(srcBuf, 0, srcBuf.length);
                if (srcCount <= 0)
                    nw = -1;
                else
                    nw = srcBuf[0];

                srcPos = 1;
            }

            peek[peekCount++] = nw;
        }

        return peek[pos];
    }

    private final String readName()
        throws IOException, XmlPullParserException {

        int pos = txtPos;
        int c = peek(0);
        if ((c < 'a' || c > 'z')
            && (c < 'A' || c > 'Z')
            && c != '_'
            && c != ':'
            && c < 0x0c0)
            exception("name expected");

        do {
            push(read());
            c = peek(0);
        }
        while ((c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '_'
            || c == '-'
            || c == ':'
            || c == '.'
            || c >= 0x0b7);

        String result = get(pos);
        txtPos = pos;
        return result;
    }

    private final void skip() throws IOException {

        while (true) {
            int c = peek(0);
            if (c > ' ' || c == -1)
                break;
            read();
        }
    }

    //--------------- public part starts here... ---------------

    public void setInput(Reader reader)
        throws XmlPullParserException {
        this.reader = reader;

        line = 1;
        column = 0;
        type = START_DOCUMENT;
        name = null;
        namespace = null;
        degenerated = false;
        attributeCount = -1;
        encoding = null;

        if (reader == null)
            return;

        srcPos = 0;
        srcCount = 0;
        peekCount = 0;
        depth = 0;

        entityMap = new Hashtable();
        entityMap.put("amp", "&");
        entityMap.put("apos", "'");
        entityMap.put("gt", ">");
        entityMap.put("lt", "<");
        entityMap.put("quot", "\"");
    }

    public void setInput(InputStream is, String _enc)
        throws XmlPullParserException {

        srcPos = 0;
        srcCount = 0;
        String enc = _enc;

        if (is == null) throw new IllegalArgumentException ();

        try {

            if (enc == null) {
                // read four bytes 

                int chk = 0;

                while (srcCount < 4) {
                    int i = is.read();
                    if (i == -1)
                        break;
                    chk = (chk << 8) | i;
                    srcBuf[srcCount++] = (char) i;
                }

                if (srcCount == 4) {
                    switch (chk) {
                        case 0x00000FEFF :
                            enc = "UTF-32BE";
                            srcCount = 0;
                            break;

                        case 0x0FFFE0000 :
                            enc = "UTF-32LE";
                            srcCount = 0;
                            break;

                        case 0x03c :
                            enc = "UTF-32BE";
                            srcBuf[0] = '<';
                            srcCount = 1;
                            break;

                        case 0x03c000000 :
                            enc = "UTF-32LE";
                            srcBuf[0] = '<';
                            srcCount = 1;
                            break;

                        case 0x0003c003f :
                            enc = "UTF-16BE";
                            srcBuf[0] = '<';
                            srcBuf[1] = '?';
                            srcCount = 2;
                            break;

                        case 0x03c003f00 :
                            enc = "UTF-16LE";
                            srcBuf[0] = '<';
                            srcBuf[1] = '?';
                            srcCount = 2;
                            break;

                        case 0x03c3f786d :
                            while (true) {
                                int i = is.read();
                                if (i == -1)
                                    break;
                                srcBuf[srcCount++] = (char) i;
                                if (i == '>') {
                                    String s =
                                        new String(
                                            srcBuf,
                                            0,
                                            srcCount);
                                    int i0 =
                                        s.indexOf("encoding");
                                    if (i0 != -1) {
                                        while (s.charAt(i0)
                                            != '"'
                                            && s.charAt(i0)
                                                != '\'')
                                            i0++;
                                        char deli =
                                            s.charAt(i0++);
                                        int i1 =
                                            s.indexOf(deli, i0);
                                        enc =
                                            s.substring(i0, i1);
                                    }
                                    break;
                                }
                            }

                        default :
                            if ((chk & 0x0ffff0000)
                                == 0x0FEFF0000) {
                                enc = "UTF-16BE";
                                srcBuf[0] =
                                    (char) ((srcBuf[2] << 8) | srcBuf[3]);
                                srcCount = 1;
                            }
                            else if (
                                (chk & 0x0ffff0000)
                                    == 0x0fffe0000) {
                                enc = "UTF-16LE";
                                srcBuf[0] =
                                    (char) ((srcBuf[3] << 8) | srcBuf[2]);
                                srcCount = 1;
                            }
                            else if (
                                (chk & 0x0ffffff00)
                                    == 0x0EFBBBF) {
                                enc = "UTF-8";
                                srcBuf[0] = srcBuf[3];
                                srcCount = 1;
                            }
                    }
                }
            }

            if (enc == null)
                enc = "UTF-8";
                
            int sc = srcCount;
            setInput(new InputStreamReader(is, enc));
            encoding = _enc;
            srcCount = sc;
        }
        catch (Exception e) {
            throw new XmlPullParserException(
                "Invalid stream or encoding: " + e.toString(),
                this,
                e);
        }
    }

    public boolean getFeature(String feature) {
        if (XmlPullParser
            .FEATURE_PROCESS_NAMESPACES
            .equals(feature))
            return processNsp;
        else if ("http://xmlpull.org/v1/doc/features.html#relaxed"
        		.equals(feature))
        	return relaxed;
        else
            return false;
    }

    public String getInputEncoding() {
        return encoding;
    }

    public void defineEntityReplacementText(
        String entity,
        String value)
        throws XmlPullParserException {
            if (entityMap == null) throw new RuntimeException 
            ("entity replacement text must be defined after setInput!");
        entityMap.put(entity, value);
    }

    public Object getProperty(String property) {
        return null;
    }

    public int getNamespaceCount(int depth) {
        if (depth > this.depth)
            throw new IndexOutOfBoundsException();
        return nspCounts[depth];
    }

    public String getNamespacePrefix(int pos) {
        return nspStack[pos << 1];
    }

    public String getNamespaceUri(int pos) {
        return nspStack[(pos << 1) + 1];
    }

    public String getNamespace(String prefix) {

        if ("xml".equals(prefix))
            return "http://www.w3.org/XML/1998/namespace";
        if ("xmlns".equals(prefix))
            return "http://www.w3.org/2000/xmlns/";

        for (int i = (getNamespaceCount(depth) << 1) - 2;
            i >= 0;
            i -= 2) {
            if (prefix == null) {
                if (nspStack[i] == null)
                    return nspStack[i + 1];
            }
            else if (prefix.equals(nspStack[i]))
                return nspStack[i + 1];
        }
        return null;
    }

    public int getDepth() {
        return depth;
    }

    public String getPositionDescription() {

        StringBuffer buf =
            new StringBuffer(
                type < TYPES.length ? TYPES[type] : "unknown");
        buf.append(' ');

        if (type == START_TAG || type == END_TAG) {
            if (degenerated)
                buf.append("(empty) ");
            buf.append('<');
            if (type == END_TAG)
                buf.append('/');

            if (prefix != null)
                buf.append("{" + namespace + "}" + prefix + ":");
            buf.append(name);

            int cnt = attributeCount << 2;
            for (int i = 0; i < cnt; i += 4) {
                buf.append(' ');
                if (attributes[i + 1] != null)
                    buf.append(
                        "{"
                            + attributes[i]
                            + "}"
                            + attributes[i
                            + 1]
                            + ":");
                buf.append(
                    attributes[i
                        + 2]
                        + "='"
                        + attributes[i
                        + 3]
                        + "'");
            }

            buf.append('>');
        }
        else if (type == IGNORABLE_WHITESPACE);
        else if (type != TEXT)
            buf.append(getText());
        else if (isWhitespace)
            buf.append("(whitespace)");
        else {
            String text = getText();
            if (text.length() > 16)
                text = text.substring(0, 16) + "...";
            buf.append(text);
        }

        buf.append(" @" + line + ":" + column);
        return buf.toString();
    }

    public int getLineNumber() {
        return line;
    }

    public int getColumnNumber() {
        return column;
    }

    public boolean isWhitespace()
        throws XmlPullParserException {
        if (type != TEXT
            && type != IGNORABLE_WHITESPACE
            && type != CDSECT)
            exception(ILLEGAL_TYPE);
        return isWhitespace;
    }

    public String getText() {
        return type < TEXT
            || (type == ENTITY_REF && unresolved) ? null : get(0);
    }

    public char[] getTextCharacters(int[] poslen) {
        if (type >= TEXT) {
            if (type == ENTITY_REF) {
                poslen[0] = 0;
                poslen[1] = name.length();
                return name.toCharArray();
            }
            poslen[0] = 0;
            poslen[1] = txtPos;
            return txtBuf;
        }

        poslen[0] = -1;
        poslen[1] = -1;
        return null;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean isEmptyElementTag()
        throws XmlPullParserException {
        if (type != START_TAG)
            exception(ILLEGAL_TYPE);
        return degenerated;
    }

    public int getAttributeCount() {
        return attributeCount;
    }

    public String getAttributeType(int index) {
        return "CDATA";
    }

    public boolean isAttributeDefault(int index) {
        return false;
    }

    public String getAttributeNamespace(int index) {
        if (index >= attributeCount)
            throw new IndexOutOfBoundsException();
        return attributes[index << 2];
    }

    public String getAttributeName(int index) {
        if (index >= attributeCount)
            throw new IndexOutOfBoundsException();
        return attributes[(index << 2) + 2];
    }

    public String getAttributePrefix(int index) {
        if (index >= attributeCount)
            throw new IndexOutOfBoundsException();
        return attributes[(index << 2) + 1];
    }

    public String getAttributeValue(int index) {
        if (index >= attributeCount)
            throw new IndexOutOfBoundsException();
        return attributes[(index << 2) + 3];
    }

    public String getAttributeValue(
        String namespace,
        String name) {

        for (int i = (attributeCount << 2) - 4;
            i >= 0;
            i -= 4) {
            if (attributes[i + 2].equals(name)
                && (namespace == null
                    || attributes[i].equals(namespace)))
                return attributes[i + 3];
        }

        return null;
    }

    public int getEventType() throws XmlPullParserException {
        return type;
    }

    public int next()
        throws XmlPullParserException, IOException {

        txtPos = 0;
        isWhitespace = true;
        int minType = 9999;
        token = false;

        do {
            nextImpl();
            if (type < minType)
                minType = type;
            //	    if (curr <= TEXT) type = curr; 
        }
        while (minType > CDSECT
            || (minType >= TEXT && peekType() >= TEXT));

        //        if (type > TEXT) type = TEXT;
        type = minType;

        return type;
    }

    public int nextToken()
        throws XmlPullParserException, IOException {

        isWhitespace = true;
        txtPos = 0;

        token = true;
        nextImpl();
        return type;
    }

    //----------------------------------------------------------------------
    // utility methods to make XML parsing easier ...

    public int nextTag()
        throws XmlPullParserException, IOException {

        next();
        if (type == TEXT && isWhitespace)
            next();

        if (type != END_TAG && type != START_TAG)
            exception("unexpected type");

        return type;
    }

    public void require(int type, String namespace, String name)
        throws XmlPullParserException, IOException {

        if (type != this.type
            || (namespace != null
                && !namespace.equals(getNamespace()))
            || (name != null && !name.equals(getName())))
            exception(
                "expected: "
                    + TYPES[type]
                    + " {"
                    + namespace
                    + "}"
                    + name);
    }

    public String nextText()
        throws XmlPullParserException, IOException {
        if (type != START_TAG)
            exception("precondition: START_TAG");

        next();

        String result;

        if (type == TEXT) {
            result = getText();
            next();
        }
        else
            result = "";

        if (type != END_TAG)
            exception("END_TAG expected");

        return result;
    }

    public void setFeature(String feature, boolean value)
        throws XmlPullParserException {
        if (XmlPullParser
            .FEATURE_PROCESS_NAMESPACES
            .equals(feature))
            processNsp = value;
        else  if ("http://xmlpull.org/v1/doc/features.html#relaxed".equals(feature))
        	relaxed = value;
        else
            exception("unsupported feature: " + feature);
    }

    public void setProperty(String property, Object value)
        throws XmlPullParserException {
        throw new XmlPullParserException(
            "unsupported property: " + property);
    }
}
