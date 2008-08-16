/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package jsyntaxpane.lang;

import javax.swing.JEditorPane;
import javax.swing.text.JTextComponent;
import javax.swing.text.Keymap;
import jsyntaxpane.KeymapUtil;
import jsyntaxpane.Lexer;
import jsyntaxpane.SyntaxLang;
import jsyntaxpane.lexers.GroovyLexer;

/**
 *
 * @author subwiz
 */
public class GroovySyntaxLang implements SyntaxLang {
    
    private static final String[] LANGS = new String[] { "groovy" };

    public String[] getLanguageNames() {
        return LANGS;
    }

    public void install(JEditorPane editorPane) {
        Keymap km_parent = editorPane.getKeymap();
        Keymap km_new = JTextComponent.addKeymap(null, km_parent);
        KeymapUtil.installCommonKeymap(km_new);
        KeymapUtil.installGroovyJavaKeymap(km_new);
        editorPane.setKeymap(km_new);
    }

    public Lexer getLexer() {
        return new GroovyLexer();
    }

}