/*
 * Copyright 2008 Ayman Al-Sairafi ayman.alsairafi@gmail.com
 * Copyright 2013-2014 Hanns Holger Rutz.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License 
 *       at http://www.apache.org/licenses/LICENSE-2.0 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.  
 */
package jsyntaxpane;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.event.DocumentEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import javax.swing.text.Segment;
import javax.swing.text.StyleConstants;

import org.netbeans.modules.editor.NbEditorDocument;

/**
 * A document that supports being highlighted.  The document maintains an
 * internal List of all the Tokens.  The Tokens are updated using
 * a Lexer, passed to it during construction.
 * 
 * @author Ayman Al-Sairafi, Hanns Holger Rutz
 */
public class SyntaxDocument extends NbEditorDocument {
    public static final String CAN_UNDO = "can-undo";
    public static final String CAN_REDO = "can-redo";

	Lexer lexer;
	List<Token> tokens;
	CompoundUndoManager undo;

    private final PropertyChangeSupport propSupport;
    private boolean canUndoState = false;
    private boolean canRedoState = false;
    
    private AbstractElement defaultRoot;
    private Vector<Element> added = new Vector<Element>();
    private Vector<Element> removed = new Vector<Element>();
    private transient Segment s;

    /**
     * Name of the attribute that specifies the tab
     * size for tabs contained in the content.  The
     * type for the value is Integer.
     */
    public static final String tabSizeAttribute = "tabSize";

    /**
     * Name of the attribute that specifies the maximum
     * length of a line, if there is a maximum length.
     * The type for the value is Integer.
     */
    public static final String lineLimitAttribute = "lineLimit";


    public SyntaxDocument(Lexer lexer, String mimeType) {
        super(mimeType);
        putProperty(PlainDocument.tabSizeAttribute, 4);
        this.lexer  = lexer;
        undo        = new CompoundUndoManager(this);    // Listen for undo and redo events
        propSupport = new PropertyChangeSupport(this);
        putProperty(tabSizeAttribute, Integer.valueOf(8));
        defaultRoot = createDefaultRoot();
    }

    /**
     * Inserts some content into the document.
     * Inserting content causes a write lock to be held while the
     * actual changes are taking place, followed by notification
     * to the observers on the thread that grabbed the write lock.
     * <p>
     * This method is thread safe, although most Swing methods
     * are not. Please see
     * <A HREF="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/index.html">Concurrency
     * in Swing</A> for more information.
     *
     * @param offs the starting offset &gt;= 0
     * @param str the string to insert; does nothing with null/empty strings
     * @param a the attributes for the inserted content
     * @exception BadLocationException  the given insert position is not a valid
     *   position within the document
     * @see Document#insertString
     */
    public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
        // fields don't want to have multiple lines.  We may provide a field-specific
        // model in the future in which case the filtering logic here will no longer
        // be needed.
        Object filterNewlines = getProperty("filterNewlines");
        if ((filterNewlines instanceof Boolean) && filterNewlines.equals(Boolean.TRUE)) {
            if ((str != null) && (str.indexOf('\n') >= 0)) {
                StringBuilder filtered = new StringBuilder(str);
                int n = filtered.length();
                for (int i = 0; i < n; i++) {
                    if (filtered.charAt(i) == '\n') {
                        filtered.setCharAt(i, ' ');
                    }
                }
                str = filtered.toString();
            }
        }
        super.insertString(offs, str, a);
    }

    /**
     * Gets the default root element for the document model.
     *
     * @return the root
     * @see Document#getDefaultRootElement
     */
    public Element getDefaultRootElement() {
        return defaultRoot;
    }

    /**
     * Creates the root element to be used to represent the
     * default document structure.
     *
     * @return the element base
     */
    protected AbstractElement createDefaultRoot() {
        BranchElement map = (BranchElement) createBranchElement(null, null);
        Element line = createLeafElement(map, null, 0, 1);
        Element[] lines = new Element[1];
        lines[0] = line;
        map.replace(0, 0, lines);
        return map;
    }

    /**
     * Get the paragraph element containing the given position.  Since this
     * document only models lines, it returns the line instead.
     */
    public Element getParagraphElement(int pos){
        Element lineMap = getDefaultRootElement();
        return lineMap.getElement( lineMap.getElementIndex( pos ) );
    }

    /**
     * Updates document structure as a result of text insertion.  This
     * will happen within a write lock.  Since this document simply
     * maps out lines, we refresh the line map.
     *
     * @param chng the change event describing the dit
     * @param attr the set of attributes for the inserted text
     */
    protected void insertUpdate(DefaultDocumentEvent chng, AttributeSet attr) {
        removed.removeAllElements();
        added.removeAllElements();
        BranchElement lineMap = (BranchElement) getDefaultRootElement();
        int offset = chng.getOffset();
        int length = chng.getLength();
        if (offset > 0) {
          offset -= 1;
          length += 1;
        }
        int index = lineMap.getElementIndex(offset);
        Element rmCandidate = lineMap.getElement(index);
        int rmOffs0 = rmCandidate.getStartOffset();
        int rmOffs1 = rmCandidate.getEndOffset();
        int lastOffset = rmOffs0;
        try {
            if (s == null) {
                s = new Segment();
            }
            getContent().getChars(offset, length, s);
            boolean hasBreaks = false;
            for (int i = 0; i < length; i++) {
                char c = s.array[s.offset + i];
                if (c == '\n') {
                    int breakOffset = offset + i + 1;
                    added.addElement(createLeafElement(lineMap, null, lastOffset, breakOffset));
                    lastOffset = breakOffset;
                    hasBreaks = true;
                }
            }
            if (hasBreaks) {
                removed.addElement(rmCandidate);
                if ((offset + length == rmOffs1) && (lastOffset != rmOffs1) &&
                    ((index+1) < lineMap.getElementCount())) {
                    Element e = lineMap.getElement(index+1);
                    removed.addElement(e);
                    rmOffs1 = e.getEndOffset();
                }
                if (lastOffset < rmOffs1) {
                    added.addElement(createLeafElement(lineMap, null, lastOffset, rmOffs1));
                }

                Element[] aelems = new Element[added.size()];
                added.copyInto(aelems);
                Element[] relems = new Element[removed.size()];
                removed.copyInto(relems);
                ElementEdit ee = new ElementEdit(lineMap, index, relems, aelems);
                chng.addEdit(ee);
                lineMap.replace(index, relems.length, aelems);
            }
            if (isComposedTextAttributeDefined(attr)) {
                insertComposedTextUpdate(chng, attr);
            }
        } catch (BadLocationException e) {
            throw new Error("Internal error: " + e.toString());
        }
        super.insertUpdate(chng, attr);
    }
    
    static boolean isComposedTextAttributeDefined(AttributeSet as) {
        return ((as != null) &&
                (as.isDefined(StyleConstants.ComposedTextAttribute)));
    }

    /**
     * Updates any document structure as a result of text removal.
     * This will happen within a write lock. Since the structure
     * represents a line map, this just checks to see if the
     * removal spans lines.  If it does, the two lines outside
     * of the removal area are joined together.
     *
     * @param chng the change event describing the edit
     */
    protected void removeUpdate(DefaultDocumentEvent chng) {
        removed.removeAllElements();
        BranchElement map = (BranchElement) getDefaultRootElement();
        int offset = chng.getOffset();
        int length = chng.getLength();
        int line0 = map.getElementIndex(offset);
        int line1 = map.getElementIndex(offset + length);
        if (line0 != line1) {
            // a line was removed
            for (int i = line0; i <= line1; i++) {
                removed.addElement(map.getElement(i));
            }
            int p0 = map.getElement(line0).getStartOffset();
            int p1 = map.getElement(line1).getEndOffset();
            Element[] aelems = new Element[1];
            aelems[0] = createLeafElement(map, null, p0, p1);
            Element[] relems = new Element[removed.size()];
            removed.copyInto(relems);
            ElementEdit ee = new ElementEdit(map, line0, relems, aelems);
            chng.addEdit(ee);
            map.replace(line0, relems.length, aelems);
        } else {
            //Check for the composed text element
            Element line = map.getElement(line0);
            if (!line.isLeaf()) {
                Element leaf = line.getElement(line.getElementIndex(offset));
                if (isComposedTextElement(leaf)) {
                    Element[] aelem = new Element[1];
                    aelem[0] = createLeafElement(map, null,
                        line.getStartOffset(), line.getEndOffset());
                    Element[] relem = new Element[1];
                    relem[0] = line;
                    ElementEdit ee = new ElementEdit(map, line0, relem, aelem);
                    chng.addEdit(ee);
                    map.replace(line0, 1, aelem);
                }
            }
        }
        super.removeUpdate(chng);
    }
    
    static boolean isComposedTextElement(Element elem) {
        AttributeSet as = elem.getAttributes();
        return isComposedTextAttributeDefined(as);
    }


    //
    // Inserts the composed text of an input method. The line element
    // where the composed text is inserted into becomes an branch element
    // which contains leaf elements of the composed text and the text
    // backing store.
    //
    private void insertComposedTextUpdate(DefaultDocumentEvent chng, AttributeSet attr) {
        added.removeAllElements();
        BranchElement lineMap = (BranchElement) getDefaultRootElement();
        int offset = chng.getOffset();
        int length = chng.getLength();
        int index = lineMap.getElementIndex(offset);
        Element elem = lineMap.getElement(index);
        int elemStart = elem.getStartOffset();
        int elemEnd = elem.getEndOffset();
        BranchElement[] abelem = new BranchElement[1];
        abelem[0] = (BranchElement) createBranchElement(lineMap, null);
        Element[] relem = new Element[1];
        relem[0] = elem;
        if (elemStart != offset)
            added.addElement(createLeafElement(abelem[0], null, elemStart, offset));
        added.addElement(createLeafElement(abelem[0], attr, offset, offset+length));
        if (elemEnd != offset+length)
            added.addElement(createLeafElement(abelem[0], null, offset+length, elemEnd));
        Element[] alelem = new Element[added.size()];
        added.copyInto(alelem);
        ElementEdit ee = new ElementEdit(lineMap, index, relem, abelem);
        chng.addEdit(ee);

        abelem[0].replace(0, 0, alelem);
        lineMap.replace(index, 1, abelem);
    }


	/*
	 * Parse the entire document and return list of tokens that do not already
	 * exist in the tokens list.  There may be overlaps, and replacements,
	 * which we will cleanup later.
	 *
	 * @return list of tokens that do not exist in the tokens field
	 */
	private void parse() {
		// if we have no lexer, then we must have no tokens...
		if (lexer == null) {
			tokens = null;
			return;
		}
		List<Token> toks = new ArrayList<Token>(getLength() / 10);
		long ts = System.nanoTime();
		int len = getLength();
		try {
			Segment seg = new Segment();
			getText(0, getLength(), seg);
			lexer.parse(seg, 0, toks);
		} catch (BadLocationException ex) {
			log.log(Level.SEVERE, null, ex);
		} finally {
			if (log.isLoggable(Level.FINEST)) {
				log.finest(String.format("Parsed %d in %d ms, giving %d tokens\n",
					len, (System.nanoTime() - ts) / 1000000, toks.size()));
			}
			tokens = toks;
		}
	}

	@Override
	protected void fireChangedUpdate(DocumentEvent e) {
		parse();
		super.fireChangedUpdate(e);
	}

	@Override
	protected void fireInsertUpdate(DocumentEvent e) {
		parse();
		super.fireInsertUpdate(e);
	}

	@Override
	protected void fireRemoveUpdate(DocumentEvent e) {
		parse();
		super.fireRemoveUpdate(e);
	}

	/**
	 * Replaces the token with the replacement string
	 */
	public void replaceToken(Token token, String replacement) {
		try {
			replace(token.start, token.length, replacement, null);
		} catch (BadLocationException ex) {
			log.log(Level.WARNING, "unable to replace token: " + token, ex);
		}
	}

	/**
	 * This class is used to iterate over tokens between two positions
	 */
	class TokenIterator implements ListIterator<Token> {

		int start;
		int end;
		int ndx = 0;

		@SuppressWarnings("unchecked")
		private TokenIterator(int start, int end) {
			this.start = start;
			this.end = end;
			if (tokens != null && !tokens.isEmpty()) {
				Token token = new Token(TokenType.COMMENT, start, end - start);
				ndx = Collections.binarySearch((List) tokens, token);
				// we will probably not find the exact token...
				if (ndx < 0) {
					// so, start from one before the token where we should be...
					// -1 to get the location, and another -1 to go back..
					ndx = (-ndx - 1 - 1 < 0) ? 0 : (-ndx - 1 - 1);
					Token t = tokens.get(ndx);
					// if the prev token does not overlap, then advance one
					if (t.end() <= start) {
						ndx++;
					}

				}
			}
		}

		@Override
		public boolean hasNext() {
			if (tokens == null) {
				return false;
			}
			if (ndx >= tokens.size()) {
				return false;
			}
			Token t = tokens.get(ndx);
            return t.start < end;
        }

		@Override
		public Token next() {
			return tokens.get(ndx++);
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean hasPrevious() {
			if (tokens == null) {
				return false;
			}
			if (ndx <= 0) {
				return false;
			}
			Token t = tokens.get(ndx);
            return t.end() > start;
        }

		@Override
		public Token previous() {
			return tokens.get(ndx--);
		}

		@Override
		public int nextIndex() {
			return ndx + 1;
		}

		@Override
		public int previousIndex() {
			return ndx - 1;
		}

		@Override
		public void set(Token e) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void add(Token e) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Returns an iterator of tokens between p0 and p1.
	 * @param start start position for getting tokens
	 * @param end position for last token
	 * @return Iterator for tokens that overall with range from start to end
	 */
	public Iterator<Token> getTokens(int start, int end) {
		return new TokenIterator(start, end);
	}

	/**
	 * Finds the token at a given position.  May return null if no token is
	 * found (whitespace skipped) or if the position is out of range:
	 */
	public Token getTokenAt(int pos) {
		if (tokens == null || tokens.isEmpty() || pos > getLength()) {
			return null;
		}
		Token tok = null;
		Token tKey = new Token(TokenType.DEFAULT, pos, 1);
		@SuppressWarnings("unchecked")
		int ndx = Collections.binarySearch((List) tokens, tKey);
		if (ndx < 0) {
			// so, start from one before the token where we should be...
			// -1 to get the location, and another -1 to go back..
			ndx = (-ndx - 1 - 1 < 0) ? 0 : (-ndx - 1 - 1);
			Token t = tokens.get(ndx);
			if ((t.start <= pos) && (pos <= t.end())) {
				tok = t;
			}
		} else {
			tok = tokens.get(ndx);
		}
		return tok;
	}

	public Token getWordAt(int offs, Pattern p) {
		Token word = null;
		try {
			Element line = getParagraphElement(offs);
			if (line == null) {
				return null;
			}
			int lineStart = line.getStartOffset();
			int lineEnd = Math.min(line.getEndOffset(), getLength());
			Segment seg = new Segment();
			getText(lineStart, lineEnd - lineStart, seg);
			if (seg.count > 0) {
				// we need to get the word using the words pattern p
				Matcher m = p.matcher(seg);
				int o = offs - lineStart;
				while (m.find()) {
					if (m.start() <= o && o <= m.end()) {
						word = new Token(TokenType.DEFAULT, m.start() + lineStart, m.end() - m.start());
						break;
					}
				}
			}
		} catch (BadLocationException ex) {
			Logger.getLogger(SyntaxDocument.class.getName()).log(Level.SEVERE, null, ex);
		}
        return word;
	}

	/**
	 * Returns the token following the current token, or null
	 * <b>This is an expensive operation, so do not use it to update the gui</b>
	 */
	public Token getNextToken(Token tok) {
		int n = tokens.indexOf(tok);
		if ((n >= 0) && (n < (tokens.size() - 1))) {
			return tokens.get(n + 1);
		} else {
			return null;
		}
	}

	/**
	 * Returns the token prior to the given token, or null
	 * <b>This is an expensive operation, so do not use it to update the gui</b>
	 */
	public Token getPrevToken(Token tok) {
		int n = tokens.indexOf(tok);
		if ((n > 0) && (!tokens.isEmpty())) {
			return tokens.get(n - 1);
		} else {
			return null;
		}
	}

	/**
	 * This is used to return the other part of a paired token in the document.
	 * A paired part has token.pairValue <> 0, and the paired token will
	 * have the negative of t.pairValue.
	 * This method properly handles nestings of same pairValues, but overlaps
	 * are not checked.
	 * if the document does not contain a paired token, then null is returned.
     *
	 * @return the other pair's token, or null if nothing is found.
	 */
	public Token getPairFor(Token t) {
		if (t == null || t.pairValue == 0) {
			return null;
		}
		Token p = null;
		int ndx = tokens.indexOf(t);
		// w will be similar to a stack. The openners weght is added to it
		// and the closers are subtracted from it (closers are already negative)
		int w = t.pairValue;
		int direction = (t.pairValue > 0) ? 1 : -1;
		boolean done = false;
		int v = Math.abs(t.pairValue);
		while (!done) {
			ndx += direction;
			if (ndx < 0 || ndx >= tokens.size()) {
				break;
			}
			Token current = tokens.get(ndx);
			if (Math.abs(current.pairValue) == v) {
				w += current.pairValue;
				if (w == 0) {
					p = current;
					done = true;
				}
			}
		}

		return p;
	}

    // public boolean isDirty() { return dirty; }

    public void setCanUndo(boolean value) {
        if (canUndoState != value) {
            // System.out.println("canUndo = " + value);
            canUndoState = value;
            propSupport.firePropertyChange(CAN_UNDO, !value, value);
        }
    }

    public void setCanRedo(boolean value) {
        if (canRedoState != value) {
            // System.out.println("canRedo = " + value);
            canRedoState = value;
            propSupport.firePropertyChange(CAN_REDO, !value, value);
        }
    }

    public void addPropertyChangeListener(String property, PropertyChangeListener listener) {
        // System.out.println("ADD " + property + " " + listener.hashCode() + " / " + this.hashCode());
        propSupport.addPropertyChangeListener(property, listener);
    }

    public void removePropertyChangeListener(String property, PropertyChangeListener listener) {
        // System.out.println("REM " + property + " " + listener.hashCode() + " / " + this.hashCode());
        propSupport.removePropertyChangeListener(property, listener);
    }

	/**
	 * Performs an undo action, if possible
	 */
	public void doUndo() {
		if (undo.canUndo()) {
			undo.undo();
			parse();
		}
	}

    public boolean canUndo() {
        return canUndoState; // undo.canUndo();
    }

	/**
	 * Performs a redo action, if possible.
	 */
	public void doRedo() {
		if (undo.canRedo()) {
			undo.redo();
			parse();
		}
	}

    public boolean canRedo() {
        return canRedoState; // undo.canRedo();
    }

    /**
     * Discards all undoable edits
     */
    public void clearUndos() {
        undo.discardAllEdits();
    }

    /**
	 * Returns a matcher that matches the given pattern on the entire document
     *
	 * @return matcher object
	 */
	public Matcher getMatcher(Pattern pattern) {
		return getMatcher(pattern, 0, getLength());
	}

	/**
	 * Returns a matcher that matches the given pattern in the part of the
	 * document starting at offset start.  Note that the matcher will have
	 * offset starting from <code>start</code>
	 *
	 * @return  matcher that <b>MUST</b> be offset by start to get the proper
	 *          location within the document
	 */
	public Matcher getMatcher(Pattern pattern, int start) {
		return getMatcher(pattern, start, getLength() - start);
	}

	/**
	 * Returns a matcher that matches the given pattern in the part of the
	 * document starting at offset start and ending at start + length.
	 * Note that the matcher will have
	 * offset starting from <code>start</code>
	 *
	 * @return matcher that <b>MUST</b> be offset by start to get the proper location within the document
	 */
	public Matcher getMatcher(Pattern pattern, int start, int length) {
		Matcher matcher = null;
		if (getLength() == 0) {
			return null;
		}
		if (start >= getLength()) {
			return null;
		}
		try {
			if (start < 0) {
				start = 0;
			}
			if (start + length > getLength()) {
				length = getLength() - start;
			}
			Segment seg = new Segment();
			getText(start, length, seg);
			matcher = pattern.matcher(seg);
		} catch (BadLocationException ex) {
			log.log(Level.SEVERE, "Requested offset: " + ex.offsetRequested(), ex);
		}
		return matcher;
	}

	/**
	 * Gets the line at given position.  The line returned will NOT include
	 * the line terminator '\n'
	 * @param pos Position (usually from text.getCaretPosition()
	 * @return the STring of text at given position
	 * @throws BadLocationException
	 */
	public String getLineAt(int pos) throws BadLocationException {
		Element e = getParagraphElement(pos);
		Segment seg = new Segment();
		getText(e.getStartOffset(), e.getEndOffset() - e.getStartOffset(), seg);
		char last = seg.last();
		if (last == '\n' || last == '\r') {
			seg.count--;
		}
		return seg.toString();
	}

	/**
	 * Deletes the line at given position
     *
	 * @throws javax.swing.text.BadLocationException
	 */
	public void removeLineAt(int pos)
		throws BadLocationException {
		Element e = getParagraphElement(pos);
		remove(e.getStartOffset(), getElementLength(e));
	}

	/**
	 * Replaces the line at given position with the given string, which can span
	 * multiple lines
     *
	 * @throws javax.swing.text.BadLocationException
	 */
	public void replaceLineAt(int pos, String newLines)
		throws BadLocationException {
		Element e = getParagraphElement(pos);
		replace(e.getStartOffset(), getElementLength(e), newLines, null);
	}

	/*
	 * Helper method to get the length of an element and avoid getting
	 * a too long element at the end of the document
	 */
	private int getElementLength(Element e) {
		int end = e.getEndOffset();
		if (end >= (getLength() - 1)) {
			end--;
		}
		return end - e.getStartOffset();
	}

	/**
	 * Gets the text without the comments. For example for the string
	 * <code>{ // it's a comment</code> this method will return "{ ".
	 * @param aStart start of the text.
	 * @param anEnd end of the text.
	 * @return String for the line without comments (if exists).
	 */
	public synchronized String getUncommentedText(int aStart, int anEnd) {
		readLock();
		StringBuilder result = new StringBuilder();
		Iterator<Token> iter = getTokens(aStart, anEnd);
		while (iter.hasNext()) {
			Token t = iter.next();
			if (!TokenType.isComment(t)) {
				result.append(t.getText(this));
			}
		}
		readUnlock();
		return result.toString();
	}

	/**
	 * Returns the starting position of the line at pos
     *
	 * @return starting position of the line
	 */
	public int getLineStartOffset(int pos) {
		return getParagraphElement(pos).getStartOffset();
	}

	/**
	 * Returns the end position of the line at pos.
	 * Does a bounds check to ensure the returned value does not exceed
	 * document length
	 */
	public int getLineEndOffset(int pos) {
		int end = 0;
		end = getParagraphElement(pos).getEndOffset();
		if (end >= getLength()) {
			end = getLength();
		}
		return end;
	}

	/**
	 * Returns the number of lines in this document
	 */
	public int getLineCount() {
		Element e = getDefaultRootElement();
        return e.getElementCount();
	}

	/**
	 * Returns the line number at given position.  The line numbers are zero based
	 */
	public int getLineNumberAt(int pos) {
        return getDefaultRootElement().getElementIndex(pos);
	}

	@Override
	public String toString() {
		return "SyntaxDocument(" + lexer + ", " + ((tokens == null) ? 0 : tokens.size()) + " tokens)@" +
			hashCode();
	}

	/**
	 * We override this here so that the replace is treated as one operation
	 * by the undomanager
     *
	 * @throws BadLocationException
	 */
	@Override
	public void replace(int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
		remove(offset, length);
		undo.startCombine();
		insertString(offset, text, attrs);
	}

	/**
	 * Appends the given string to the text of this document.
     *
	 * @return this document
	 */
	public SyntaxDocument append(String str) {
		try {
			insertString(getLength(), str, null);
		} catch (BadLocationException ex) {
			log.log(Level.WARNING, "Error appending str", ex);
		}
		return this;
	}

    // our logger instance...
	private static final Logger log = Logger.getLogger(SyntaxDocument.class.getName());
}
