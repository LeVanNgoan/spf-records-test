package vn.orderrecorder.shopee;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopeeNotificationListener extends NotificationListenerService {
    private static final String SHOPEE="com.shopeepay.merchant.vn";
    private static volatile ShopeeNotificationListener instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,PendingIntent> intents=new ConcurrentHashMap<>();

    @Override public void onListenerConnected(){
        super.onListenerConnected();instance=this;
        if(AppPrefs.ensureStableCoreMigration(this))AppLog.add(this,"Đã làm sạch state automation thử nghiệm cũ · dữ liệu đơn được giữ nguyên");
        if(AppPrefs.ensureRetrySafetyMigration(this))AppLog.add(this,"v2.0.3: đã xóa queue/state retry cũ · dữ liệu đơn và Hub được giữ nguyên");
        HubSync.start(this);
        AutoStartReceiver.scheduleWatchdog(this);
        AppLog.add(this,"SERVICE NOTIFICATION connected · v2.0.6 black-box · burst-safe core unchanged");
        handler.postDelayed(this::tryStartNext,180L);
    }

    @Override public void onListenerDisconnected(){
        AppLog.add(this,"SERVICE NOTIFICATION disconnected");
        if(instance==this)instance=null;
        super.onListenerDisconnected();
    }

    @Override public void onDestroy(){
        AppLog.add(this,"SERVICE NOTIFICATION destroyed");
        if(instance==this)instance=null;
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(!AppPrefs.isEnabled(this)||sbn==null||!SHOPEE.equals(sbn.getPackageName()))return;
        String combined=notificationText(sbn);String id=TextParser.notificationId(combined);
        if(id.isEmpty()){
            AppLog.add(this,"NOTIFY_UNPARSED · chars="+combined.length()+" · postAge="+Math.max(0L,System.currentTimeMillis()-sbn.getPostTime())+"ms");
            return;
        }
        PendingIntent pi=sbn.getNotification().contentIntent;if(pi!=null)intents.put(id,pi);
        OrderStore.recordNotification(this,id,sbn.getPostTime());AppPrefs.enqueue(this,id);
        AppLog.add(this,"NOTIFY #"+id+" · postAge="+Math.max(0L,System.currentTimeMillis()-sbn.getPostTime())+"ms · contentIntent="+(pi!=null)+" · queue="+AppPrefs.queueSize(this));
        if(AppPrefs.isAutoEnabled(this)){
            long grace=Math.max(0L,1000L-(System.currentTimeMillis()-sbn.getPostTime()));
            handler.postDelayed(this::tryStartNext,grace);
        }
    }

    public static void requestNext(){
        ShopeeNotificationListener x=instance;if(x!=null)x.handler.postDelayed(x::tryStartNext,90L);
    }

    private void tryStartNext(){
        if(!AppPrefs.isEnabled(this)||!AppPrefs.isAutoEnabled(this)||AppPrefs.isProcessing(this))return;
        String id=AppPrefs.peekQueue(this);
        long now=System.currentTimeMillis();
        int rotated=0;
        int scanLimit=Math.max(1,AppPrefs.queueSize(this));
        long earliestWake=Long.MAX_VALUE;
        while(!id.isEmpty()&&rotated<scanLimit){
            OrderRecord queued=OrderStore.get(this,id);
            if(queued==null||queued.isCompleted()){
                AppPrefs.removeFromQueue(this,id);intents.remove(id);id=AppPrefs.peekQueue(this);scanLimit=Math.max(scanLimit,AppPrefs.queueSize(this));continue;
            }
            boolean terminal="needs_review".equals(queued.status)
                    || queued.attemptCount>=AutomationPolicy.MAX_ATTEMPTS
                    || queued.receivedAt<=0L
                    || now-queued.receivedAt>AutomationPolicy.MAX_AUTO_AGE_MS;
            if(terminal){
                OrderStore.markNeedsReview(this,id,"auto_expired_or_attempt_limit");
                ReviewNotifier.notifyNeedsReview(this, OrderStore.get(this,id));
                AppLog.add(this,"AUTO #"+id+": dừng vĩnh viễn auto-retry · chuyển Cần kiểm tra + đã cảnh báo người dùng");
                AppPrefs.removeFromQueue(this,id);intents.remove(id);id=AppPrefs.peekQueue(this);scanLimit=Math.max(scanLimit,AppPrefs.queueSize(this));continue;
            }
            long waitUntil=Math.max(queued.eligibleAt,queued.nextAttemptAt);
            if(waitUntil>now){
                earliestWake=Math.min(earliestWake,waitUntil);
                // A retry/backoff order must never block a fresh order behind it during rush hour.
                AppPrefs.moveToQueueEnd(this,id);
                rotated++;
                id=AppPrefs.peekQueue(this);
                continue;
            }
            break;
        }
        if(id.isEmpty()||rotated>=scanLimit){
            if(earliestWake!=Long.MAX_VALUE){
                long delay=Math.max(60L,Math.min(earliestWake-System.currentTimeMillis(),1000L));
                handler.postDelayed(this::tryStartNext,delay);
            }
            return;
        }

        // Every order gets its own 1-second sound grace, even when it waited behind another order.
        OrderRecord pending=OrderStore.get(this,id);
        if(pending!=null&&pending.receivedAt>0L){
            long remaining=AutomationPolicy.SOUND_GRACE_MS-(System.currentTimeMillis()-pending.receivedAt);
            if(remaining>0L){handler.postDelayed(this::tryStartNext,remaining);return;}
        }

        AppPrefs.beginProcessing(this,id);
        OrderStore.beginAttempt(this,id,"stable_core");
        OrderRecord attemptRecord=OrderStore.get(this,id);
        AppLog.add(this,"ATTEMPT #"+id+" · n="+(attemptRecord==null?"?":attemptRecord.attemptCount)+"/"+AutomationPolicy.MAX_ATTEMPTS+" · age="+(attemptRecord==null||attemptRecord.receivedAt<=0?"?":(System.currentTimeMillis()-attemptRecord.receivedAt)+"ms")+" · queue="+AppPrefs.queueSize(this));
        scheduleAttemptTimeout(id);
        PendingIntent pi=intents.get(id);
        if(pi==null){StatusBarNotification n=findActiveNotification(id);if(n!=null)pi=n.getNotification().contentIntent;}

        if(pi!=null){
            try{
                sendContentIntent(pi);
                AppLog.add(this,"AUTO #"+id+": đã kích hoạt contentIntent");
                final String expected=id;
                handler.postDelayed(()->verifyOrOpenShade(expected),750L);
                return;
            }catch(Exception e){
                AppLog.add(this,"AUTO #"+id+": contentIntent không mở được ("+e.getClass().getSimpleName()+")");
            }
        }else{
            AppLog.add(this,"AUTO #"+id+": notification không có contentIntent dùng được");
        }
        ShopeeAccessibilityService.requestNotificationTapFallback(id);
    }

    private void verifyOrOpenShade(String id){
        if(!id.equals(AppPrefs.getProcessingOrder(this)))return;
        if(id.equals(AppPrefs.getActiveDetailOrder(this))||!AppPrefs.STAGE_OPENING_ORDER.equals(AppPrefs.getStage(this)))return;
        AppLog.add(this,"AUTO #"+id+": chưa vào đúng Chi tiết đơn → mở thanh thông báo để click thật");
        ShopeeAccessibilityService.requestNotificationTapFallback(id);
    }

    public static void reportAttemptFailure(String orderId,String reason){
        ShopeeNotificationListener x=instance;
        if(x!=null)x.handler.post(()->x.failOrDefer(orderId,reason));
    }

    private void scheduleAttemptTimeout(String id){
        handler.postDelayed(()->{
            if(!id.equals(AppPrefs.getProcessingOrder(this))||OrderStore.isCompleted(this,id))return;
            AppLog.add(this,"AUTO #"+id+": quá thời gian 1 lần xử lý → dừng lần này");
            failOrDefer(id,"attempt_timeout");
        },AutomationPolicy.STALE_PROCESSING_MS);
    }

    private void failOrDefer(String id,String reason){
        if(id==null||id.isEmpty()||!id.equals(AppPrefs.getProcessingOrder(this)))return;
        boolean terminal=OrderStore.failAttempt(this,id,reason);
        OrderRecord failed=OrderStore.get(this,id);
        AppLog.add(this,"ATTEMPT_RESULT #"+id+" · reason="+reason+" · terminal="+terminal+" · n="+(failed==null?"?":failed.attemptCount)+" · stage="+AppPrefs.getStage(this)+" · queue="+AppPrefs.queueSize(this));
        if(terminal){
            AppLog.add(this,"AUTO #"+id+": đã thử đủ giới hạn/đơn đã quá cũ → Cần kiểm tra, KHÔNG tự mở lại");
            ReviewNotifier.notifyNeedsReview(this, OrderStore.get(this,id));
            AppPrefs.finishProcessing(this,id);intents.remove(id);
            handler.postDelayed(this::tryStartNext,120L);
        }else{
            AppLog.add(this,"AUTO #"+id+": hoãn lần thử hiện tại · sẽ thử lại có giới hạn");
            AppPrefs.deferProcessing(this,id);
            handler.postDelayed(this::tryStartNext,120L);
        }
    }

    private void sendContentIntent(PendingIntent pi)throws PendingIntent.CanceledException{
        if(Build.VERSION.SDK_INT>=34){
            ActivityOptions options=ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(this,0,null,null,null,null,options.toBundle());
        }else pi.send();
    }

    private StatusBarNotification findActiveNotification(String id){
        try{
            StatusBarNotification[] all=getActiveNotifications();if(all==null)return null;
            for(StatusBarNotification s:all){
                if(s!=null&&SHOPEE.equals(s.getPackageName())&&id.equals(TextParser.notificationId(notificationText(s))))return s;
            }
        }catch(Exception ignored){}
        return null;
    }

    private static String notificationText(StatusBarNotification sbn){
        Bundle e=sbn.getNotification().extras;
        return value(e.getCharSequence(Notification.EXTRA_TITLE))+"\n"+value(e.getCharSequence(Notification.EXTRA_TEXT))+"\n"+value(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
    }
    private static String value(CharSequence s){return s==null?"":s.toString();}
}
