package vn.orderrecorder.shopee;

import android.content.Context;
import android.content.SharedPreferences;

public final class HubPrefs {
    private static final String FILE="hub_prefs";
    private static final String URL="hub_url";
    private static final String KEY="hub_key";
    private static final String LAST_OK="hub_last_ok";
    private static final String LAST_ERROR="hub_last_error";
    private HubPrefs() {}
    private static SharedPreferences p(Context c){return c.getSharedPreferences(FILE,Context.MODE_PRIVATE);}

    public static String getUrl(Context c){return p(c).getString(URL,"");}
    public static String getKey(Context c){return p(c).getString(KEY,"");}
    public static boolean isConfigured(Context c){return !getUrl(c).isEmpty()&&!getKey(c).isEmpty();}
    public static void save(Context c,String url,String key){
        p(c).edit().putString(URL,normalizeUrl(url)).putString(KEY,key==null?"":key.trim()).remove(LAST_ERROR).apply();
    }
    public static void clear(Context c){p(c).edit().clear().apply();}
    public static void markOk(Context c){p(c).edit().putLong(LAST_OK,System.currentTimeMillis()).remove(LAST_ERROR).apply();}
    public static void markError(Context c,String e){p(c).edit().putString(LAST_ERROR,e==null?"":e).apply();}
    public static long lastOk(Context c){return p(c).getLong(LAST_OK,0L);}
    public static String lastError(Context c){return p(c).getString(LAST_ERROR,"");}

    public static String normalizeUrl(String raw){
        String s=raw==null?"":raw.trim();
        if(s.isEmpty())return "";
        while(s.endsWith("/"))s=s.substring(0,s.length()-1);
        if(!s.startsWith("http://")&&!s.startsWith("https://"))s="http://"+s;
        String after=s.substring(s.indexOf("://")+3);
        if(!after.contains(":"))s=s+":17891";
        return s;
    }
}
