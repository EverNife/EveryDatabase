package br.com.finalcraft.everydatabase.manager.testdata;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A nested <b>value object</b> (not an entity - it has no manager and no key). Lives inside an
 * {@link Inventory}, which lives inside a {@code Player}. Its list of lore lines and map of
 * enchantments give the codec a deep object graph to round-trip.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    private String material;
    private int amount;
    private List<String> lore;
    private Map<String, Integer> enchants;

    public Item(String material, int amount) {
        this(material, amount, new ArrayList<>(), new HashMap<>());
    }
}
