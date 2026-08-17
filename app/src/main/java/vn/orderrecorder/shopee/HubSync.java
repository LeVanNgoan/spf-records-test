package vn.orderrecorder.shopee;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HubSync {
    private static final String VERSION="2.0.6";
    private static final ScheduledExecutorService EXEC=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"order-hub-sync");t.setDaemon(true);return t;});
    private static final AtomicBoolean STARTED=new AtomicBoolean(false),RUNNING=new AtomicBoolean(false);
    private HubSync(){}
    public static void start(Context c){Context app=c.getApplicationContext();if(STARTED.compareAndSet(false,true))EXEC.scheduleWithFixedDelay(()->tick(app),4,30,TimeUnit.SECONDS);kick(app);}
    public static void kick(Context c){Context app=c.getApplicationContext();EXEC.execute(()->tick(app));}
    public static int pendingCount(Context c){int n=0;for(OrderRecord r:OrderStore.getAll(c))if(r.isCompleted()&&!r.hubSynced&&!r.businessOrderCode().isEmpty())n++;return n;}
    private static void tick(Context c){if(!HubPrefs.isConfigured(c))return;syncNow(c);sendHeartbeat(c);}

    /** Kiểm tra thật cả LAN + port + API key bằng endpoint có xác thực, không chỉ /api/health. */
    public static void test(Context c,Callback cb){Context app=c.getApplicationContext();EXEC.execute(()->{String err="";boolean ok=false;try{
        String base=HubPrefs.getUrl(app),key=HubPrefs.getKey(app);if(base.isEmpty())throw new IOException("Chưa nhập địa chỉ Hub");if(key.isEmpty())throw new IOException("Chưa nhập API key");
        HttpURLConnection x=(HttpURLConnection)new URL(base+"/api/heartbeat").openConnection();x.setConnectTimeout(2500);x.setReadTimeout(3000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);
        JSONObject o=new JSONObject();o.put("source","shopeefood-sunmi");o.put("platform","shopeefood");o.put("deviceName",Build.MANUFACTURER+" "+Build.MODEL);o.put("status","connection_test");o.put("pending",pendingCount(app));o.put("version",VERSION);
        byte[] body=o.toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();ok=code>=200&&code<300;if(!ok)err="HTTP "+code;try(InputStream is=ok?x.getInputStream():x.getErrorStream()){if(is!=null)while(is.read()!=-1){}}x.disconnect();
        if(ok)HubPrefs.markOk(app);else HubPrefs.markError(app,err);
    }catch(Exception e){err=shortError(e);HubPrefs.markError(app,err);}if(cb!=null)cb.done(ok,err);});}

    private static void syncNow(Context c){if(!HubPrefs.isConfigured(c)||!RUNNING.compareAndSet(false,true))return;try{List<OrderRecord> all=OrderStore.getAll(c);for(int i=all.size()-1;i>=0;i--){OrderRecord r=all.get(i);if(!r.isCompleted()||r.hubSynced||r.businessOrderCode().isEmpty())continue;try{post(c,r);OrderStore.markHubSynced(c,r.shortOrderId,r.receivedAt,System.currentTimeMillis());HubPrefs.markOk(c);AppLog.add(c,"Hub: đã đồng bộ "+r.businessOrderCode());}catch(Exception e){String err=shortError(e);HubPrefs.markError(c,err);AppLog.add(c,"Hub: chưa gửi được · "+err);break;}}}finally{RUNNING.set(false);}}

    /** Gửi lại toàn bộ đơn hoàn tất còn lưu local (tối đa 7 ngày) bất kể hubSynced. */
    public static void forceResync7Days(Context c, ForceCallback cb){
        Context app=c.getApplicationContext();
        EXEC.execute(()->{
            if(!HubPrefs.isConfigured(app)){if(cb!=null)cb.done(false,0,0,"Chưa cấu hình Hub");return;}
            if(!RUNNING.compareAndSet(false,true)){if(cb!=null)cb.done(false,0,0,"Hub đang đồng bộ, thử lại sau vài giây");return;}
            int sent=0,skipped=0;String error="";boolean ok=true;
            try{
                List<OrderRecord> all=OrderStore.getAll(app);
                // getAll trả mới -> cũ, force-resync theo thứ tự cũ -> mới để Hub dựng lại timeline tự nhiên.
                for(int i=all.size()-1;i>=0;i--){
                    OrderRecord r=all.get(i);
                    if(r==null||!r.isCompleted()){skipped++;continue;}
                    if(r.businessOrderCode().isEmpty()){skipped++;AppLog.add(app,"Force resync: bỏ qua đơn thiếu mã #xxxx · short #"+r.shortOrderId);continue;}
                    try{
                        post(app,r);
                        OrderStore.markHubSynced(app,r.shortOrderId,r.receivedAt,System.currentTimeMillis());
                        sent++;
                    }catch(Exception e){
                        ok=false;error=shortError(e);HubPrefs.markError(app,error);AppLog.add(app,"Force resync dừng: "+error);break;
                    }
                }
                if(ok){HubPrefs.markOk(app);AppLog.add(app,"Force resync 7 ngày hoàn tất · "+sent+" đơn");}
            }finally{
                RUNNING.set(false);
                sendHeartbeat(app);
                if(cb!=null)cb.done(ok,sent,skipped,error);
            }
        });
    }

    private static void sendHeartbeat(Context c){try{String base=HubPrefs.getUrl(c),key=HubPrefs.getKey(c);HttpURLConnection x=(HttpURLConnection)new URL(base+"/api/heartbeat").openConnection();x.setConnectTimeout(2500);x.setReadTimeout(3000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);JSONObject o=new JSONObject();o.put("source","shopeefood-sunmi");o.put("platform","shopeefood");o.put("deviceName",Build.MANUFACTURER+" "+Build.MODEL);o.put("status",AppPrefs.isEnabled(c)?(AppPrefs.isAutoEnabled(c)?"ready":"auto_off"):"recording_off");o.put("pending",pendingCount(c));o.put("version",VERSION);byte[] body=o.toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();if(code>=200&&code<300){HubPrefs.markOk(c);try(InputStream is=x.getInputStream()){while(is.read()!=-1){}}}else HubPrefs.markError(c,"Heartbeat HTTP "+code);x.disconnect();}catch(Exception e){HubPrefs.markError(c,shortError(e));}}

    private static void post(Context c,OrderRecord r)throws Exception{String base=HubPrefs.getUrl(c),key=HubPrefs.getKey(c);HttpURLConnection x=(HttpURLConnection)new URL(base+"/api/orders").openConnection();x.setConnectTimeout(3000);x.setReadTimeout(4000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);byte[] body=payload(r).toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();if(code<200||code>=300){String msg=readSmall(x.getErrorStream());throw new IOException("HTTP "+code+(msg.isEmpty()?"":" · "+msg));}try(InputStream is=x.getInputStream()){while(is.read()!=-1){}}x.disconnect();}

    private static JSONObject payload(OrderRecord r)throws Exception{JSONObject o=new JSONObject();o.put("platform","shopeefood");o.put("orderCode",r.businessOrderCode());o.put("shortOrderNumber",r.shortOrderId);o.put("displayOrderId",r.displayOrderId);o.put("fullOrderId",r.fullOrderId);o.put("phone",TextParser.normalizePhone(r.receiverPhone));o.put("receivedAt",iso(r.receivedAt));o.put("recordedAt",iso(r.phoneRecordedAt>0?r.phoneRecordedAt:r.updatedAt));o.put("sourceDevice","ShopeeFood "+Build.MANUFACTURER+" "+Build.MODEL);o.put("syncId",!r.fullOrderId.isEmpty()?"spf:full:"+r.fullOrderId:"spf:"+r.receivedAt+":"+r.businessOrderCode());return o;}
    private static String iso(long ms){if(ms<=0)ms=System.currentTimeMillis();SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));return f.format(new Date(ms));}
    private static String readSmall(InputStream in){if(in==null)return"";try(BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String s=b.readLine();return s==null?"":s.substring(0,Math.min(120,s.length()));}catch(Exception e){return"";}}
    private static String shortError(Exception e){String s=e.getMessage();if(s==null||s.trim().isEmpty())s=e.getClass().getSimpleName();return s.length()>100?s.substring(0,100):s;}
    public interface Callback{void done(boolean ok,String error);}
    public interface ForceCallback{void done(boolean ok,int sent,int skipped,String error);}
}
