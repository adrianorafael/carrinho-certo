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
    }

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) { super(c, "carrinho.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, store TEXT, created_at INTEGER NOT NULL, finished_at INTEGER, open INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, name TEXT NOT NULL, unit_cents INTEGER NOT NULL, qty REAL NOT NULL, by_weight INTEGER NOT NULL DEFAULT 0, barcode TEXT, photo_path TEXT, label_photo_path TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_items_trip ON items(trip_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

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
                "SELECT t.id, t.store, t.created_at, t.finished_at, t.open, COUNT(i.id), COALESCE(SUM(CAST(ROUND(i.unit_cents*i.qty) AS INTEGER)),0) " +
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
        String sql = "SELECT t.id, t.store, t.created_at, t.finished_at, t.open, COUNT(i.id), COALESCE(SUM(CAST(ROUND(i.unit_cents*i.qty) AS INTEGER)),0) " +
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
                "SELECT id, trip_id, name, unit_cents, qty, by_weight, barcode, photo_path, label_photo_path, created_at FROM items WHERE trip_id=? ORDER BY id DESC",
                new String[]{String.valueOf(tripId)})) {
            while (c.moveToNext()) out.add(readItem(c));
        }
        return out;
    }

    public Item item(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, trip_id, name, unit_cents, qty, by_weight, barcode, photo_path, label_photo_path, created_at FROM items WHERE id=?",
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
}
