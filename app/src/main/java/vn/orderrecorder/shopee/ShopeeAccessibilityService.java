package vn.orderrecorder.shopee;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.List;

public final class ShopeeAccessibilityService extends AccessibilityService {
    private static final String SHOPEE="com.shopeepay.merchant.vn";
    private static final String SYSTEM_UI="com.android.systemui";
    private static volatile ShopeeAccessibilityService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private long lastShopeeScan=0L,lastPhoneScan=0L,lastContactLog=0L,lastNoPhoneLog=0L;
    private long lastDetailClick=0L,lastReceiverClick=0L,lastMismatchLog=0L,lastMismatchRecover=0L,lastCompletedLog=0L;
    private String lastExternalPkg="";

    @Override protected void onServiceConnected(){
        instance=this;
        // Whichever Android service reconnects first after update must kill stale retry state.
        AppPrefs.ensureRetrySafetyMigration(this);
        HubSync.start(this);
        AutoStartReceiver.scheduleWatchdog(this);
        AppLog.add(this,"SERVICE ACCESSIBILITY connected · v2.0.6 black-box · burst-safe core unchanged");
    }

    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}

    public static void requestNotificationTapFallback(String orderId){
        ShopeeAccessibilityService x=instance;
        if(x==null){ShopeeNotificationListener.requestNext();return;}
        x.handler.post(()->x.openAndTapNotification(orderId));
    }

    private void openAndTapNotification(String orderId){
        if(orderId==null||orderId.isEmpty())return;
        if(!orderId.equals(AppPrefs.getProcessingOrder(this)))return;
        boolean opened=performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        AppLog.add(this,opened?"AUTO #"+orderId+": đã mở thanh thông báo":"AUTO #"+orderId+": không mở được thanh thông báo");
        if(opened)handler.postDelayed(()->tapNotificationFromShade(orderId,0),320L);
    }

    private void tapNotificationFromShade(String orderId,int attempt){
        if(!orderId.equals(AppPrefs.getProcessingOrder(this)))return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(NodeUtil.clickNotificationForOrder(root,orderId)){
            AppLog.add(this,"AUTO #"+orderId+": đã click đúng card thông báo");
            handler.postDelayed(()->verifyNotificationTap(orderId),900L);
            return;
        }
        if(attempt<6){
            handler.postDelayed(()->tapNotificationFromShade(orderId,attempt+1),180L);
        }else{
            AppLog.add(this,"AUTO #"+orderId+": không tìm thấy card notification; hoãn đơn để tránh kẹt state");
            performGlobalAction(GLOBAL_ACTION_BACK);
            ShopeeNotificationListener.reportAttemptFailure(orderId,"notification_card_not_found");
        }
    }

    private void verifyNotificationTap(String orderId){
        if(!orderId.equals(AppPrefs.getProcessingOrder(this)))return;
        if(orderId.equals(AppPrefs.getActiveDetailOrder(this))||!AppPrefs.STAGE_OPENING_ORDER.equals(AppPrefs.getStage(this)))return;
        AppLog.add(this,"AUTO #"+orderId+": đã click notification nhưng chưa thấy đúng Chi tiết đơn; hoãn và thử lại sau");
        ShopeeNotificationListener.reportAttemptFailure(orderId,"notification_open_verify_failed");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!AppPrefs.isEnabled(this)||event==null)return;
        String pkg=event.getPackageName()==null?"":event.getPackageName().toString();

        if(SHOPEE.equals(pkg)){
            if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED)inspectShopeeClick(event.getSource());
            scheduleShopeeScan();
            return;
        }
        if(getPackageName().equals(pkg)||SYSTEM_UI.equals(pkg))return;

        if(AppPrefs.isPhoneCaptureActive(this))schedulePhoneScan(pkg,eventSnapshot(event));
    }

    private void scheduleShopeeScan(){
        long now=System.currentTimeMillis();if(now-lastShopeeScan<55L)return;
        lastShopeeScan=now;handler.postDelayed(this::scanShopee,18L);
    }

    private void scanShopee(){
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        String text=NodeUtil.text(root);

        if(text.contains("Chi tiết đơn hàng")){
            TextParser.Detail d=TextParser.detail(text);
            if(!d.shortId.isEmpty()){
                String previous=AppPrefs.getActiveDetailOrder(this);
                if(!previous.isEmpty()&&!previous.equals(d.shortId))AppPrefs.clearContactBinding(this);
                AppPrefs.setActiveDetailOrder(this,d.shortId);
                OrderStore.updateDetails(this,d.shortId,d.display,d.full);

                if(AppPrefs.isAutoEnabled(this)&&!OrderStore.isCompleted(this,d.shortId)&&OrderStore.canContinueAuto(this,d.shortId)){
                    String processing=AppPrefs.getProcessingOrder(this);
                    if(processing.isEmpty()){
                        // FIX v0.2.5: nếu notification automation thất bại nhưng người dùng tự mở đơn,
                        // automation tiếp quản chính đơn ĐANG NHÌN THẤY thay vì đứng chết ở CONTACT_SHEET.
                        AppPrefs.adoptVisibleOrder(this,d.shortId);
                        processing=d.shortId;
                        AppLog.add(this,"AUTO tiếp quản đơn đang mở #"+d.shortId);
                    }
                    if(d.shortId.equals(processing)){
                        AppPrefs.setStage(this,AppPrefs.STAGE_DETAIL);
                        autoOpenContact(root,processing);
                    }else{
                        logMismatch(processing,d.shortId);
                        recoverExpectedOrder(processing);
                    }
                }
            }
        }

        if(text.contains("Liên hệ khách hàng")&&text.contains("Khách nhận đơn")){
            String boundOrder=AppPrefs.getActiveDetailOrder(this);
            if(boundOrder.isEmpty()){
                throttledLog("Thấy bảng Liên hệ nhưng chưa xác định được mã đơn · không ghi SĐT",2500L);
                return;
            }

            String processing=AppPrefs.getProcessingOrder(this);
            if(AppPrefs.isAutoEnabled(this)&&!OrderStore.isCompleted(this,boundOrder)&&OrderStore.canContinueAuto(this,boundOrder)&&processing.isEmpty()){
                AppPrefs.adoptVisibleOrder(this,boundOrder);
                processing=boundOrder;
                AppLog.add(this,"AUTO tiếp quản bảng Liên hệ của đơn #"+boundOrder);
            }

            // Không được đổi stage/contact binding nếu queue đang trỏ sang đơn khác.
            if(AppPrefs.isAutoEnabled(this)&&!processing.isEmpty()&&!boundOrder.equals(processing)){
                logMismatch(processing,boundOrder);
                recoverExpectedOrder(processing);
                return;
            }

            Rect receiver=NodeUtil.findLabelBounds(root,"Khách nhận đơn");
            Rect purchaser=NodeUtil.findLabelBounds(root,"Khách hàng");
            int ry=receiver==null?Integer.MIN_VALUE:receiver.centerY();
            int py=purchaser==null?Integer.MIN_VALUE:purchaser.centerY();
            AppPrefs.markContactSheet(this,boundOrder,ry,py);
            AppPrefs.setStage(this,AppPrefs.STAGE_CONTACT_SHEET);

            long now=System.currentTimeMillis();
            if(now-lastContactLog>1800L){
                lastContactLog=now;
                AppLog.add(this,"Đã khóa bảng Liên hệ vào #"+boundOrder+" · chỉ dùng Khách nhận đơn");
            }

            if(OrderStore.isCompleted(this,boundOrder)){
                if(now-lastCompletedLog>2500L){lastCompletedLog=now;AppLog.add(this,"Đơn #"+boundOrder+" đã có SĐT · không ghi đè");}
                return;
            }

            String receiverPhone=NodeUtil.singlePhoneOnRow(root,"Khách nhận đơn","Khách hàng");
            if(!receiverPhone.isEmpty()){
                saveReceiverPhone(boundOrder,receiverPhone,"trực tiếp dòng Khách nhận đơn",false);
                return;
            }

            if(AppPrefs.isAutoEnabled(this)&&OrderStore.canContinueAuto(this,boundOrder))autoClickReceiver(root,boundOrder);
        }
    }

    private void autoOpenContact(AccessibilityNodeInfo root,String id){
        if(id==null||id.isEmpty()||OrderStore.isCompleted(this,id)||!OrderStore.canContinueAuto(this,id))return;
        long now=System.currentTimeMillis();if(now-lastDetailClick<260L)return;
        String stage=AppPrefs.getStage(this);
        if(AppPrefs.STAGE_CONTACT_SHEET.equals(stage)||AppPrefs.STAGE_READING_PHONE.equals(stage)||AppPrefs.STAGE_OPENING_DIALER.equals(stage)||AppPrefs.STAGE_OPENING_CONTACT.equals(stage)&&AppPrefs.stageAge(this)<650L)return;
        lastDetailClick=now;

        boolean nodeClick=NodeUtil.clickPhoneOnRow(root,"Khách hàng");
        boolean gesture=false;
        if(!nodeClick)gesture=tapRightSideOfRow(root,"Khách hàng");
        if(nodeClick||gesture){
            AppPrefs.setStage(this,AppPrefs.STAGE_OPENING_CONTACT);
            OrderStore.setStatus(this,id,"opening_contact");
            AppLog.add(this,"AUTO #"+id+": mở bảng Liên hệ ("+(nodeClick?"node":"gesture")+")");
        }else if(AppPrefs.stageAge(this)>650L){
            AppLog.add(this,"AUTO #"+id+": chưa bấm được nút Liên hệ; sẽ thử lại");
        }
    }

    private void autoClickReceiver(AccessibilityNodeInfo root,String id){
        if(id==null||id.isEmpty()||!id.equals(AppPrefs.getContactOrder(this))||OrderStore.isCompleted(this,id)||!OrderStore.canContinueAuto(this,id))return;
        long now=System.currentTimeMillis();if(now-lastReceiverClick<260L)return;
        lastReceiverClick=now;

        // Khóa ID trước khi click. Dù Dialer mở nhanh, số chỉ có thể vào đúng id này.
        if(!AppPrefs.beginPhoneCapture(this,id))return;
        AppPrefs.setStage(this,AppPrefs.STAGE_OPENING_DIALER);
        boolean nodeClick=NodeUtil.clickPhoneOnRow(root,"Khách nhận đơn");
        boolean gesture=false;
        if(!nodeClick)gesture=tapRightSideOfRow(root,"Khách nhận đơn");
        if(nodeClick||gesture){
            OrderStore.setStatus(this,id,"opening_dialer");
            AppLog.add(this,"AUTO #"+id+": bấm đúng Khách nhận đơn ("+(nodeClick?"node":"gesture")+") · chờ SĐT");
        }else{
            AppPrefs.clearPhoneCapture(this);
            AppPrefs.setStage(this,AppPrefs.STAGE_CONTACT_SHEET);
            AppLog.add(this,"AUTO #"+id+": chưa bấm được icon Khách nhận đơn; sẽ thử lại");
        }
    }

    private boolean tapRightSideOfRow(AccessibilityNodeInfo root,String label){
        Rect lr=NodeUtil.findLabelBounds(root,label);if(lr==null||lr.isEmpty())return false;
        Rect wr=new Rect();root.getBoundsInScreen(wr);if(wr.isEmpty())return false;
        float density=getResources().getDisplayMetrics().density;
        float x=wr.right-Math.max(48f*density,wr.width()*0.055f);
        float y=lr.centerY();
        Path path=new Path();path.moveTo(x,y);
        GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(path,0,70);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);
    }

    private void inspectShopeeClick(AccessibilityNodeInfo source){
        if(!AppPrefs.contactSheetWasRecent(this))return;
        String orderId=AppPrefs.getContactOrder(this);if(orderId.isEmpty())return;
        String around=NodeUtil.text(source);
        boolean receiver=around.contains("Khách nhận đơn")||NodeUtil.clickMatchesCachedReceiverRow(source,AppPrefs.getReceiverY(this),AppPrefs.getPurchaserY(this));
        if(!receiver)return; // click Khách hàng không bao giờ arm capture.
        if(OrderStore.isCompleted(this,orderId))return;
        if(AppPrefs.beginPhoneCapture(this,orderId))AppLog.add(this,"MANUAL: xác nhận click Khách nhận đơn · khóa SĐT vào #"+orderId);
    }

    private void schedulePhoneScan(String pkg,String eventText){
        long now=System.currentTimeMillis();if(now-lastPhoneScan<55L)return;
        lastPhoneScan=now;handler.postDelayed(()->scanPhone(pkg,eventText),18L);
    }

    private void scanPhone(String pkg,String eventText){
        if(!AppPrefs.isPhoneCaptureActive(this))return;
        String capture=AppPrefs.getCaptureOrder(this);if(capture.isEmpty())return;
        String processing=AppPrefs.getProcessingOrder(this);
        if(AppPrefs.isAutoEnabled(this)&&!processing.isEmpty()&&!capture.equals(processing)){
            AppLog.add(this,"CHẶN ghép sai: processing #"+processing+" nhưng Dialer thuộc #"+capture);
            AppPrefs.clearContactBinding(this);return;
        }

        AccessibilityNodeInfo root=getRootInActiveWindow();String phone=NodeUtil.singleLikelyPhone(root,eventText);
        if(phone.isEmpty()){
            long now=System.currentTimeMillis();
            if(!pkg.equals(lastExternalPkg)||now-lastNoPhoneLog>1800L){
                lastExternalPkg=pkg;lastNoPhoneLog=now;AppLog.add(this,"Dialer "+pkg+" · đang chờ đúng 1 SĐT VN");
            }
            return;
        }
        saveReceiverPhone(capture,phone,"Dialer sau Khách nhận đơn",true);
    }

    private void saveReceiverPhone(String orderId,String phone,String source,boolean cameFromDialer){
        String processing=AppPrefs.getProcessingOrder(this);
        OrderStore.PhoneAttachResult result=OrderStore.attachPhoneStrict(this,orderId,phone);
        if(result.code==OrderStore.PhoneAttachResult.MISSING){
            AppLog.add(this,"Đọc được SĐT nhưng không có record #"+orderId+" · KHÔNG ghi");AppPrefs.clearContactBinding(this);return;
        }
        if(result.code==OrderStore.PhoneAttachResult.CONFLICT){
            AppLog.add(this,"CHẶN ghi đè #"+orderId+": đã có SĐT khác");AppPrefs.clearContactBinding(this);
            if(orderId.equals(processing))AppPrefs.finishProcessing(this,orderId);return;
        }

        boolean newlySaved=result.code==OrderStore.PhoneAttachResult.SAVED;String doneId=result.record.shortOrderId;
        AppLog.add(this,(newlySaved?"Đã lưu":"SĐT trùng dữ liệu cũ của")+" #"+doneId+" · nguồn: "+source);
        if(newlySaved && result.record.processingStartedAt>0L && result.record.phoneRecordedAt>=result.record.processingStartedAt){
            long workMs=result.record.phoneRecordedAt-result.record.processingStartedAt;
            long totalMs=result.record.receivedAt>0L?result.record.phoneRecordedAt-result.record.receivedAt:workMs;
            AppLog.add(this,"PERF #"+doneId+": xử lý "+workMs+"ms · từ thông báo "+totalMs+"ms");
        }
        if(newlySaved){
            Toast.makeText(this,"Đã ghi SĐT Khách nhận đơn #"+doneId,Toast.LENGTH_LONG).show();
            ReviewNotifier.cancel(this,result.record);
            CompletionNotifier.notifyRecorded(this,result.record);
            // Local save is already complete. Hub sync is asynchronous and must never block capture.
            HubSync.kick(this);
        }

        boolean wasProcessing=!processing.isEmpty()&&orderId.equals(processing);
        if(wasProcessing)AppPrefs.finishProcessing(this,doneId);else AppPrefs.clearContactBinding(this);

        // Burst-safe turnover: if another order is already queued, start it immediately.
        // Do NOT spend ~0.8s backing out first; the next order's contentIntent will replace the current screen.
        // This preserves the simple one-order-at-a-time engine while increasing throughput during rushes.
        boolean hasNext=AppPrefs.queueSize(this)>0;
        if(hasNext){
            AppLog.add(this,"BURST: #"+doneId+" đã lưu · chuyển ngay sang đơn kế tiếp");
            ShopeeNotificationListener.requestNext();
        }else if(cameFromDialer){
            robustReturnToShopee(doneId,false);
        }else{
            handler.postDelayed(()->{
                performGlobalAction(GLOBAL_ACTION_BACK);
                AppLog.add(this,"Hoàn tất #"+doneId+" · đóng bảng Liên hệ");
            },180L);
        }
    }

    private void robustReturnToShopee(String doneId,boolean requestNext){
        backUntilShopee(doneId,0,requestNext);
    }

    private void backUntilShopee(String doneId,int attempt,boolean requestNext){
        handler.postDelayed(()->{
            if(isShopeeForeground()){
                AppLog.add(this,"Hoàn tất #"+doneId+" · đã về Shopee Partner");
                if(requestNext)ShopeeNotificationListener.requestNext();return;
            }
            if(attempt<3){
                performGlobalAction(GLOBAL_ACTION_BACK);
                backUntilShopee(doneId,attempt+1,requestNext);
            }else{
                bringShopeeToFront();
                AppLog.add(this,"Hoàn tất #"+doneId+" · yêu cầu đưa Shopee Partner lên trước");
                if(requestNext)handler.postDelayed(ShopeeNotificationListener::requestNext,100L);
            }
        },attempt==0?180L:260L);
    }

    private boolean isShopeeForeground(){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        return root!=null&&root.getPackageName()!=null&&SHOPEE.equals(root.getPackageName().toString());
    }

    private void bringShopeeToFront(){
        try{
            Intent i=getPackageManager().getLaunchIntentForPackage(SHOPEE);
            if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);}
        }catch(Exception e){AppLog.add(this,"Không thể đưa Shopee Partner lên foreground");}
    }

    private void recoverExpectedOrder(String expected){
        if(expected==null||expected.isEmpty()||!AppPrefs.isAutoEnabled(this)||!OrderStore.canContinueAuto(this,expected))return;
        long now=System.currentTimeMillis();if(now-lastMismatchRecover<1200L)return;
        lastMismatchRecover=now;
        AppLog.add(this,"AUTO khôi phục: mở lại notification của #"+expected+" thay vì kẹt ở đơn khác");
        requestNotificationTapFallback(expected);
    }

    private void logMismatch(String expected,String visible){
        long now=System.currentTimeMillis();if(now-lastMismatchLog<1600L)return;
        lastMismatchLog=now;AppLog.add(this,"CHẶN sai đơn: cần #"+expected+" nhưng màn hình là #"+visible);
    }

    private void throttledLog(String msg,long interval){
        long now=System.currentTimeMillis();if(now-lastMismatchLog<interval)return;lastMismatchLog=now;AppLog.add(this,msg);
    }

    private static String eventSnapshot(AccessibilityEvent e){
        StringBuilder b=new StringBuilder();List<CharSequence> xs=e.getText();
        if(xs!=null)for(CharSequence x:xs)if(x!=null)b.append(x).append('\n');
        CharSequence d=e.getContentDescription();if(d!=null)b.append(d).append('\n');
        CharSequence c=e.getClassName();if(c!=null)b.append(c).append('\n');return b.toString();
    }

    @Override public void onInterrupt(){AppLog.add(this,"SERVICE ACCESSIBILITY interrupted");}
    @Override public void onDestroy(){AppLog.add(this,"SERVICE ACCESSIBILITY destroyed");if(instance==this)instance=null;super.onDestroy();}
}
