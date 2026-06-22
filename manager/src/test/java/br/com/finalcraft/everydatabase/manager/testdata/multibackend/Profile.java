package br.com.finalcraft.everydatabase.manager.testdata.multibackend;

import br.com.finalcraft.everydatabase.manager.Ref;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Root entity for the multi-backend example. It is stored in one database, and each of its five
 * references uses a <b>different key type</b> and resolves through a manager backed by a
 * <b>different database</b>.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Profile {

    private UUID uuid;                          // key: UUID

    private Ref<String, Clan>      clan;        // String key   -> PostgreSQL
    private Ref<Long, Wallet>      wallet;      // Long key     -> MongoDB
    private Ref<Integer, Stats>    stats;       // Integer key  -> H2
    private Ref<Home.Key, Home>    home;        // record key   -> LocalFile
    private Ref<Session.Id, Session> session;   // record key   -> InMemory
}
