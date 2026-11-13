package com.example.stepbystep;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;


public class DBHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "stepbystep.db";
    // อย่าเปลี่ยนเลขนี้ เพราะ onUpgrade จะ DROP TABLE ทำให้ประวัติหายทั้งหมด
    private static final int DATABASE_VERSION = 1;


    public static final String TABLE_SESSIONS = "sessions";
    public static final String COL_ID = "id";
    public static final String COL_START_TIME = "start_time";
    public static final String COL_END_TIME = "end_time";
    public static final String COL_DURATION_MS = "duration_ms";
    public static final String COL_STEPS = "steps";
    public static final String COL_SHAKES = "shakes";
    public static final String COL_STEPS_PER_MIN = "steps_per_min";
    public static final String COL_SHAKES_PER_MIN = "shakes_per_min";
    public static final String COL_NEW_STEP_RECORD = "is_new_step_record";
    public static final String COL_NEW_SHAKE_RECORD = "is_new_shake_record";

    private static final String SQL_CREATE_SESSIONS =
            "CREATE TABLE " + TABLE_SESSIONS + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_START_TIME + " INTEGER NOT NULL, "
                    + COL_END_TIME + " INTEGER NOT NULL, "
                    + COL_DURATION_MS + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STEPS + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_SHAKES + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STEPS_PER_MIN + " REAL NOT NULL DEFAULT 0, "
                    + COL_SHAKES_PER_MIN + " REAL NOT NULL DEFAULT 0, "
                    + COL_NEW_STEP_RECORD + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_NEW_SHAKE_RECORD + " INTEGER NOT NULL DEFAULT 0"
                    + ")";

    public DBHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_SESSIONS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // ช่วงพัฒนา: ล้างตารางแล้วสร้างใหม่เมื่อเปลี่ยน schema
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSIONS);
        onCreate(db);
    }

    // =====================================================================
    // ข้อ 6: Insert Session
    // =====================================================================

    /**
     * ผลลัพธ์ของการบันทึก 1 กิจกรรม
     * id = -1 หมายถึง insert ไม่สำเร็จ
     */
    public static final class InsertResult {
        public final long id;
        public final boolean isNewStepRecord;
        public final boolean isNewShakeRecord;

        InsertResult(long id, boolean isNewStepRecord, boolean isNewShakeRecord) {
            this.id = id;
            this.isNewStepRecord = isNewStepRecord;
            this.isNewShakeRecord = isNewShakeRecord;
        }

        public boolean isSuccess() {
            return id != -1;
        }
    }

    /**
     * บันทึกกิจกรรมลง SQLite พร้อมตัดสินว่าเป็นสถิติใหม่หรือไม่
     *
     * New Record = ค่าของเซสชันนี้ > 0 และ มากกว่าค่าสูงสุดเดิมในฐานข้อมูล (strictly greater)
     * ทำ MAX query + INSERT ใน transaction เดียว เพื่อให้การเทียบสถิติกับการเขียนเป็น atomic
     */
    public InsertResult insertSession(long startTime, long endTime, long durationMs,
                                      int steps, int shakes,
                                      double stepsPerMin, double shakesPerMin) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int[] best = queryBest(db);
            boolean newStep = steps > 0 && steps > best[0];
            boolean newShake = shakes > 0 && shakes > best[1];

            ContentValues cv = new ContentValues();
            cv.put(COL_START_TIME, startTime);
            cv.put(COL_END_TIME, endTime);
            cv.put(COL_DURATION_MS, durationMs);
            cv.put(COL_STEPS, steps);
            cv.put(COL_SHAKES, shakes);
            cv.put(COL_STEPS_PER_MIN, stepsPerMin);
            cv.put(COL_SHAKES_PER_MIN, shakesPerMin);
            cv.put(COL_NEW_STEP_RECORD, newStep ? 1 : 0);
            cv.put(COL_NEW_SHAKE_RECORD, newShake ? 1 : 0);

            long id = db.insert(TABLE_SESSIONS, null, cv);
            if (id != -1) db.setTransactionSuccessful();
            return new InsertResult(id, id != -1 && newStep, id != -1 && newShake);
        } finally {
            db.endTransaction();
        }
    }

    // =====================================================================
    // ข้อ 5: Total & New Record
    // =====================================================================

    /** ยอดรวมจากประวัติทั้งหมด: [0] = ก้าวรวม, [1] = เขย่ารวม */
    public int[] getTotals() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT IFNULL(SUM(" + COL_STEPS + "),0), IFNULL(SUM(" + COL_SHAKES + "),0) FROM "
                        + TABLE_SESSIONS, null)) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1)};
        }
        return new int[]{0, 0};
    }

    /** สถิติสูงสุดเท่าที่มีในประวัติ: [0] = ก้าวสูงสุด, [1] = เขย่าสูงสุด */
    public int[] getBestRecords() {
        return queryBest(getReadableDatabase());
    }

    private static int[] queryBest(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery(
                "SELECT IFNULL(MAX(" + COL_STEPS + "),0), IFNULL(MAX(" + COL_SHAKES + "),0) FROM "
                        + TABLE_SESSIONS, null)) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1)};
        }
        return new int[]{0, 0};
    }

    // =====================================================================
    // ข้อ 7: Query & Persistence
    // =====================================================================

    /** ดึงประวัติกิจกรรมทั้งหมด เรียงใหม่สุดก่อน */
    public List<SessionRecord> getAllSessions() {
        List<SessionRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_SESSIONS, null, null, null, null, null,
                COL_START_TIME + " DESC, " + COL_ID + " DESC")) {
            while (c.moveToNext()) {
                list.add(new SessionRecord(
                        c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        c.getLong(c.getColumnIndexOrThrow(COL_START_TIME)),
                        c.getLong(c.getColumnIndexOrThrow(COL_END_TIME)),
                        c.getLong(c.getColumnIndexOrThrow(COL_DURATION_MS)),
                        c.getInt(c.getColumnIndexOrThrow(COL_STEPS)),
                        c.getInt(c.getColumnIndexOrThrow(COL_SHAKES)),
                        c.getDouble(c.getColumnIndexOrThrow(COL_STEPS_PER_MIN)),
                        c.getDouble(c.getColumnIndexOrThrow(COL_SHAKES_PER_MIN)),
                        c.getInt(c.getColumnIndexOrThrow(COL_NEW_STEP_RECORD)) == 1,
                        c.getInt(c.getColumnIndexOrThrow(COL_NEW_SHAKE_RECORD)) == 1));
            }
        }
        return list;
    }

    // =====================================================================
    // ข้อ 8: Delete & Recalculate
    // =====================================================================

    /**
     * ลบกิจกรรมตาม id แล้วคำนวณ flag "สถิติใหม่" ของแถวที่เหลือใหม่
     * ทำใน transaction เดียว คืน true ถ้าลบสำเร็จ
     */
    public boolean deleteSession(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.delete(TABLE_SESSIONS, COL_ID + " = ?",
                    new String[]{String.valueOf(id)});
            if (rows > 0) {
                recalculateRecordFlags(db);
                db.setTransactionSuccessful();
            }
            return rows > 0;
        } finally {
            db.endTransaction();
        }
    }

    /** ไล่ตามลำดับเวลา แล้วตั้ง flag สถิติใหม่ตามกติกาเดียวกับ insertSession */
    private static void recalculateRecordFlags(SQLiteDatabase db) {
        int maxSteps = 0;
        int maxShakes = 0;
        try (Cursor c = db.query(TABLE_SESSIONS,
                new String[]{COL_ID, COL_STEPS, COL_SHAKES},
                null, null, null, null,
                COL_START_TIME + " ASC, " + COL_ID + " ASC")) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                int steps = c.getInt(1);
                int shakes = c.getInt(2);

                boolean newStep = steps > 0 && steps > maxSteps;
                boolean newShake = shakes > 0 && shakes > maxShakes;
                if (newStep) maxSteps = steps;
                if (newShake) maxShakes = shakes;

                ContentValues cv = new ContentValues();
                cv.put(COL_NEW_STEP_RECORD, newStep ? 1 : 0);
                cv.put(COL_NEW_SHAKE_RECORD, newShake ? 1 : 0);
                db.update(TABLE_SESSIONS, cv, COL_ID + " = ?",
                        new String[]{String.valueOf(id)});
            }
        }
    }
}