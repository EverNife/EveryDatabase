package br.com.finalcraft.everydatabase.manager.testdata;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A nested <b>value object</b> (not an entity) embedded in a {@code Player}: a titled, sized
 * container holding a list of {@link Item}s. Exercises a non-trivial Jackson graph (object →
 * list → object → list/map) in the codec round-trip, so a single rich entity replaces several
 * trivial ones.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Inventory {

    private String title;
    private int maxSize;
    private List<Item> items;

    public Inventory(String title, int maxSize) {
        this(title, maxSize, new ArrayList<>());
    }
}
