package br.com.carrinhocerto.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Banco local (SQLite). Uma "compra" (trip) tem vários itens. Só existe uma compra aberta por vez. */
public final class Db extends SQLiteOpenHelper {

    public static final class Item {
        public long id;
        public long tripId;
        public String name = "";
        public long unitCents;
        public double qty = 1;
        public boolean byWeight;
        public String barcode;
        public String photoPath;
        public String labelPhotoPath;
        public long createdAt;
        public long listItemId;   // item da lista de compras ao qual foi vinculado (0 = nenhum)

        public long subtotal() { return Math.round(unitCents * qty); }
    }

    public static final class Trip {
        public long id;
        public String store;
        public long createdAt;
        public long finishedAt;
        public boolean open;
        public int itemCount;
        public long totalCents;
        public long listId;       // lista usada nesta compra (0 = nenhuma)
        public boolean checked;   // nota fiscal conferida
    }

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) { super(c, "carrinho.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, store TEXT, created_at INTEGER NOT NULL, finished_at INTEGER, open INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, name TEXT NOT NULL, unit_cents INTEGER NOT NULL, qty REAL NOT NULL, by_weight INTEGER NOT NULL DEFAULT 0, barcode TEXT, photo_path TEXT, label_photo_path TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_items_trip ON items(trip_id)");
        upgradeTo2(db);
    }

    /** Migrações só acrescentam: nunca apagam nem alteram dados existentes. */
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) upgradeTo2(db);
    }

    private static void upgradeTo2(SQLiteDatabase db) {
        addColumn(db, "items", "list_item_id INTEGER");
        addColumn(db, "trips", "list_id INTEGER");
        addColumn(db, "trips", "checked INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE IF NOT EXISTS lists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, used_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS list_items (id INTEGER PRIMARY KEY AUTOINCREMENT, list_id INTEGER NOT NULL, name TEXT NOT NULL, qty REAL NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_list_items ON list_items(list_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS list_missing (trip_id INTEGER NOT NULL, list_item_id INTEGER NOT NULL, PRIMARY KEY(trip_id, list_item_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS receipts (trip_id INTEGER PRIMARY KEY, created_at INTEGER NOT NULL, total_cents INTEGER, diff_cents INTEGER, json TEXT NOT NULL)");
    }

    private static void addColumn(SQLiteDatabase db, String table, String def) {
        String col = def.split(" ")[0];
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) if (col.equals(c.getString(1))) return;
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + def);
    }

    // ------------------------------------------------------------ compras

    /** Retorna a compra aberta, criando uma se não existir. */
    public Trip openTrip() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT id FROM trips WHERE open=1 ORDER BY id DESC LIMIT 1", null)) {
            if (c.moveToFirst()) return trip(c.getLong(0));
        }
        ContentValues v = new ContentValues();
        v.put("created_at", System.currentTimeMillis());
        v.put("open", 1);
        long id = db.insert("trips", null, v);
        return trip(id);
    }

    public Trip trip(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.id, t.store, t.created_at, t.finished_at, t.open, COUNT(i.id), COALESCE(SUM(CAST(ROUND(i.unit_cents*i.qty) AS INTEGER)),0), COALESCE(t.list_id,0), t.checked " +
                        "FROM trips t LEFT JOIN items i ON i.trip_id=t.id WHERE t.id=? GROUP BY t.id", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readTrip(c) : null;
        }
    }

    private static Trip readTrip(Cursor c) {
        Trip t = new Trip();
        t.id = c.getLong(0);
        t.store = c.isNull(1) ? null : c.getString(1);
        t.createdAt = c.getLong(2);
        t.finishedAt = c.isNull(3) ? 0 : c.getLong(3);
        t.open = c.getInt(4) == 1;
        t.itemCount = c.getInt(5);
        t.totalCents = c.getLong(6);
        t.listId = c.getLong(7);
        t.checked = c.getInt(8) == 1;
        return t;
    }

    public void setStore(long tripId, String store) {
        ContentValues v = new ContentValues();
        v.put("store", store == null || store.trim().isEmpty() ? null : store.trim());
        getWritableDatabase().update("trips", v, "id=?", new String[]{String.valueOf(tripId)});
    }

    public void finishTrip(long tripId) {
        ContentValues v = new ContentValues();
        v.put("open", 0);
        v.put("finished_at", System.currentTimeMillis());
        getWritableDatabase().update("trips", v, "id=?", new String[]{String.valueOf(tripId)});
    }

    public void reopenTrip(long tripId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE trips SET open=0, finished_at=COALESCE(finished_at, ?) WHERE open=1 AND id<>? AND id IN (SELECT trip_id FROM items)",
                new Object[]{System.currentTimeMillis(), tripId});
        db.execSQL("DELETE FROM trips WHERE open=1 AND id<>? AND id NOT IN (SELECT trip_id FROM items)", new Object[]{tripId});
        ContentValues v = new ContentValues();
        v.put("open", 1);
        db.update("trips", v, "id=?", new String[]{String.valueOf(tripId)});
    }

    public void deleteTrip(long tripId) {
        for (Item i : items(tripId)) deleteFiles(i);
        SQLiteDatabase db = getWritableDatabase();
        db.delete("items", "trip_id=?", new String[]{String.valueOf(tripId)});
        db.delete("list_missing", "trip_id=?", new String[]{String.valueOf(tripId)});
        db.delete("receipts", "trip_id=?", new String[]{String.valueOf(tripId)});
        db.delete("trips", "id=?", new String[]{String.valueOf(tripId)});
    }

    /** Compras finalizadas dentro do mês (year, month 0-11), opcionalmente filtradas por estabelecimento. */
    public List<Trip> finishedTrips(int year, int month, String storeFilter) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month, 1);
        long from = cal.getTimeInMillis();
        cal.add(Calendar.MONTH, 1);
        long to = cal.getTimeInMillis();
        String sql = "SELECT t.id, t.store, t.created_at, t.finished_at, t.open, COUNT(i.id), COALESCE(SUM(CAST(ROUND(i.unit_cents*i.qty) AS INTEGER)),0), COALESCE(t.list_id,0), t.checked " +
                "FROM trips t LEFT JOIN items i ON i.trip_id=t.id WHERE t.open=0 AND t.finished_at>=? AND t.finished_at<? " +
                (storeFilter != null ? "AND t.store=? " : "") + "GROUP BY t.id ORDER BY t.finished_at DESC";
        String[] args = storeFilter != null
                ? new String[]{String.valueOf(from), String.valueOf(to), storeFilter}
                : new String[]{String.valueOf(from), String.valueOf(to)};
        List<Trip> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(readTrip(c));
        }
        return out;
    }

    public List<String> stores() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT store FROM trips WHERE store IS NOT NULL AND store<>'' GROUP BY store ORDER BY MAX(created_at) DESC", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    // ------------------------------------------------------------ itens

    public List<Item> items(long tripId) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, trip_id, name, unit_cents, qty, by_weight, barcode, photo_path, label_photo_path, created_at, COALESCE(list_item_id,0) FROM items WHERE trip_id=? ORDER BY id DESC",
                new String[]{String.valueOf(tripId)})) {
            while (c.moveToNext()) out.add(readItem(c));
        }
        return out;
    }

    public Item item(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, trip_id, name, unit_cents, qty, by_weight, barcode, photo_path, label_photo_path, created_at, COALESCE(list_item_id,0) FROM items WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readItem(c) : null;
        }
    }

    private static Item readItem(Cursor c) {
        Item i = new Item();
        i.id = c.getLong(0);
        i.tripId = c.getLong(1);
        i.name = c.getString(2);
        i.unitCents = c.getLong(3);
        i.qty = c.getDouble(4);
        i.byWeight = c.getInt(5) == 1;
        i.barcode = c.isNull(6) ? null : c.getString(6);
        i.photoPath = c.isNull(7) ? null : c.getString(7);
        i.labelPhotoPath = c.isNull(8) ? null : c.getString(8);
        i.createdAt = c.getLong(9);
        i.listItemId = c.getLong(10);
        return i;
    }

    /** Procura no carrinho aberto um item igual (mesmo código de barras, ou mesmo nome e preço). */
    public Item findDuplicate(long tripId, String barcode, String name, long unitCents, long excludeId) {
        for (Item i : items(tripId)) {
            if (i.id == excludeId) continue;
            if (barcode != null && !barcode.isEmpty() && barcode.equals(i.barcode)) return i;
            if (i.unitCents == unitCents && i.name.trim().equalsIgnoreCase(name.trim())) return i;
        }
        return null;
    }

    public long save(Item i) {
        ContentValues v = new ContentValues();
        v.put("trip_id", i.tripId);
        v.put("name", i.name);
        v.put("unit_cents", i.unitCents);
        v.put("qty", i.qty);
        v.put("by_weight", i.byWeight ? 1 : 0);
        v.put("barcode", i.barcode);
        v.put("photo_path", i.photoPath);
        v.put("label_photo_path", i.labelPhotoPath);
        if (i.listItemId > 0) v.put("list_item_id", i.listItemId); else v.putNull("list_item_id");
        SQLiteDatabase db = getWritableDatabase();
        if (i.id > 0) {
            db.update("items", v, "id=?", new String[]{String.valueOf(i.id)});
        } else {
            i.createdAt = System.currentTimeMillis();
            v.put("created_at", i.createdAt);
            i.id = db.insert("items", null, v);
        }
        return i.id;
    }

    /** Remove só a linha do banco (arquivos ficam, para permitir "Desfazer"). */
    public void deleteItemRow(long id) {
        getWritableDatabase().delete("items", "id=?", new String[]{String.valueOf(id)});
    }

    public static void deleteFiles(Item i) {
        if (i.photoPath != null) new File(i.photoPath).delete();
        if (i.labelPhotoPath != null) new File(i.labelPhotoPath).delete();
    }

    // ============================================================ listas de compras

    public static final class ShoppingList {
        public long id;
        public String name;
        public long createdAt, usedAt;
        public int itemCount;
    }

    public static final class ListItem {
        public long id, listId;
        public String name = "";
        public double qty = 1;
        public boolean priority;
        public int position;
    }

    public static final int PENDING = 0, IN_CART = 1, MISSING = 2;

    /** Linha da lista durante a compra: o estado é calculado a partir do carrinho aberto. */
    public static final class ListRow {
        public ListItem item;
        public int state = PENDING;
        public double cartQty;
        public long cartCents;
        public PriceHint last;      // último preço conhecido (para estimar pendentes)
    }

    public static final class PriceHint {
        public long cents;
        public String store;
        public long when;
        public String name;
        public boolean byWeight;
    }

    public List<ShoppingList> lists() {
        List<ShoppingList> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT l.id, l.name, l.created_at, COALESCE(l.used_at,0), COUNT(li.id) FROM lists l LEFT JOIN list_items li ON li.list_id=l.id " +
                        "GROUP BY l.id ORDER BY MAX(COALESCE(l.used_at,0), l.created_at) DESC", null)) {
            while (c.moveToNext()) {
                ShoppingList l = new ShoppingList();
                l.id = c.getLong(0); l.name = c.getString(1); l.createdAt = c.getLong(2); l.usedAt = c.getLong(3); l.itemCount = c.getInt(4);
                out.add(l);
            }
        }
        return out;
    }

    public ShoppingList list(long id) {
        for (ShoppingList l : lists()) if (l.id == id) return l;
        return null;
    }

    public long createList(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("lists", null, v);
    }

    public void renameList(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("lists", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteList(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM list_missing WHERE list_item_id IN (SELECT id FROM list_items WHERE list_id=?)", new Object[]{id});
        db.delete("list_items", "list_id=?", new String[]{String.valueOf(id)});
        db.delete("lists", "id=?", new String[]{String.valueOf(id)});
        db.execSQL("UPDATE trips SET list_id=NULL WHERE list_id=?", new Object[]{id});
    }

    public long duplicateList(long id, String newName) {
        long nid = createList(newName);
        for (ListItem li : listItems(id)) {
            li.id = 0;
            li.listId = nid;
            saveListItem(li);
        }
        return nid;
    }

    public List<ListItem> listItems(long listId) {
        List<ListItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, list_id, name, qty, priority, position FROM list_items WHERE list_id=? ORDER BY position, id",
                new String[]{String.valueOf(listId)})) {
            while (c.moveToNext()) {
                ListItem li = new ListItem();
                li.id = c.getLong(0); li.listId = c.getLong(1); li.name = c.getString(2); li.qty = c.getDouble(3);
                li.priority = c.getInt(4) == 1; li.position = c.getInt(5);
                out.add(li);
            }
        }
        return out;
    }

    public ListItem listItem(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT list_id FROM list_items WHERE id=?", new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            for (ListItem li : listItems(c.getLong(0))) if (li.id == id) return li;
        }
        return null;
    }

    public long saveListItem(ListItem li) {
        ContentValues v = new ContentValues();
        v.put("list_id", li.listId);
        v.put("name", li.name);
        v.put("qty", li.qty);
        v.put("priority", li.priority ? 1 : 0);
        SQLiteDatabase db = getWritableDatabase();
        if (li.id > 0) {
            v.put("position", li.position);
            db.update("list_items", v, "id=?", new String[]{String.valueOf(li.id)});
        } else {
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position),0)+1 FROM list_items WHERE list_id=?", new String[]{String.valueOf(li.listId)})) {
                c.moveToFirst();
                li.position = c.getInt(0);
            }
            v.put("position", li.position);
            li.id = db.insert("list_items", null, v);
        }
        return li.id;
    }

    public void deleteListItem(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("list_items", "id=?", new String[]{String.valueOf(id)});
        db.delete("list_missing", "list_item_id=?", new String[]{String.valueOf(id)});
        db.execSQL("UPDATE items SET list_item_id=NULL WHERE list_item_id=?", new Object[]{id});
    }

    /** Grava a nova ordem (lista de ids na ordem desejada). */
    public void reorderList(List<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) db.execSQL("UPDATE list_items SET position=? WHERE id=?", new Object[]{i + 1, ids.get(i)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void setTripList(long tripId, long listId) {
        SQLiteDatabase db = getWritableDatabase();
        if (listId > 0) {
            db.execSQL("UPDATE trips SET list_id=? WHERE id=?", new Object[]{listId, tripId});
            db.execSQL("UPDATE lists SET used_at=? WHERE id=?", new Object[]{System.currentTimeMillis(), listId});
        } else {
            db.execSQL("UPDATE trips SET list_id=NULL WHERE id=?", new Object[]{tripId});
        }
    }

    public void setMissing(long tripId, long listItemId, boolean missing) {
        SQLiteDatabase db = getWritableDatabase();
        if (missing) db.execSQL("INSERT OR IGNORE INTO list_missing(trip_id, list_item_id) VALUES(?,?)", new Object[]{tripId, listItemId});
        else db.delete("list_missing", "trip_id=? AND list_item_id=?", new String[]{String.valueOf(tripId), String.valueOf(listItemId)});
    }

    /** Linhas da lista com o estado na compra (tripId); tripId=0 mostra tudo pendente. */
    public List<ListRow> listRows(long listId, long tripId) {
        List<ListRow> out = new ArrayList<>();
        java.util.Set<Long> missing = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT list_item_id FROM list_missing WHERE trip_id=?", new String[]{String.valueOf(tripId)})) {
            while (c.moveToNext()) missing.add(c.getLong(0));
        }
        java.util.Map<Long, double[]> cart = new java.util.HashMap<>();
        if (tripId > 0) {
            for (Item i : items(tripId)) {
                if (i.listItemId <= 0) continue;
                double[] a = cart.get(i.listItemId);
                if (a == null) { a = new double[2]; cart.put(i.listItemId, a); }
                a[0] += i.byWeight ? 1 : i.qty;
                a[1] += i.subtotal();
            }
        }
        List<HistItem> hist = history(600);
        for (ListItem li : listItems(listId)) {
            ListRow r = new ListRow();
            r.item = li;
            double[] a = cart.get(li.id);
            if (a != null) { r.state = IN_CART; r.cartQty = a[0]; r.cartCents = (long) a[1]; }
            else if (missing.contains(li.id)) r.state = MISSING;
            r.last = lastPrice(hist, null, li.name, li.id);
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------ histórico de preços

    private static final class HistItem {
        long listItemId; String name; String barcode; long cents; boolean byWeight; String store; long when;
    }

    /** Itens de compras finalizadas, do mais recente para o mais antigo. */
    private List<HistItem> history(int limit) {
        List<HistItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(i.list_item_id,0), i.name, i.barcode, i.unit_cents, i.by_weight, t.store, t.finished_at FROM items i JOIN trips t ON t.id=i.trip_id " +
                        "WHERE t.open=0 ORDER BY t.finished_at DESC, i.id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                HistItem h = new HistItem();
                h.listItemId = c.getLong(0); h.name = c.getString(1); h.barcode = c.isNull(2) ? null : c.getString(2);
                h.cents = c.getLong(3); h.byWeight = c.getInt(4) == 1; h.store = c.getString(5); h.when = c.getLong(6);
                out.add(h);
            }
        }
        return out;
    }

    /** Último preço pago pelo mesmo produto: mesmo código, mesmo item de lista ou nome muito parecido. */
    public PriceHint lastPrice(String barcode, String name, long listItemId) {
        return lastPrice(history(600), barcode, name, listItemId);
    }

    private static PriceHint lastPrice(List<HistItem> hist, String barcode, String name, long listItemId) {
        br.com.carrinhocerto.core.ProductMatcher.Product target = new br.com.carrinhocerto.core.ProductMatcher.Product(name, barcode, 0);
        HistItem found = null;
        for (HistItem h : hist) {
            if (barcode != null && !barcode.isEmpty() && barcode.equals(h.barcode)) { found = h; break; }
            if (listItemId > 0 && listItemId == h.listItemId) { found = h; break; }
        }
        if (found == null && name != null && !name.trim().isEmpty()) {
            for (HistItem h : hist) {
                br.com.carrinhocerto.core.ProductMatcher.Match m = br.com.carrinhocerto.core.ProductMatcher.compare(
                        target, new br.com.carrinhocerto.core.ProductMatcher.Product(h.name, h.barcode, 0));
                if (m.confidence == br.com.carrinhocerto.core.ProductMatcher.Confidence.HIGH
                        || (listItemId > 0 && m.confidence == br.com.carrinhocerto.core.ProductMatcher.Confidence.MEDIUM)) { found = h; break; }
            }
        }
        if (found == null) return null;
        PriceHint p = new PriceHint();
        p.cents = found.cents; p.store = found.store; p.when = found.when; p.name = found.name; p.byWeight = found.byWeight;
        return p;
    }
}
