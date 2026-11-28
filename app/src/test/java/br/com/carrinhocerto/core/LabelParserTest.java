package br.com.carrinhocerto.core;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class LabelParserTest {
    @Test public void casosDeEtiqueta() {
        List<String> fails = LabelParserCases.run();
        assertTrue(String.join("\n", fails), fails.isEmpty());
    }

    @Test public void comparadorDeProdutos() {
        List<String> fails = MatcherCases.run();
        assertTrue(String.join("\n", fails), fails.isEmpty());
    }
}
