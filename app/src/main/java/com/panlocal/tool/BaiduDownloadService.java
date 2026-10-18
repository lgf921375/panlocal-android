package com.panlocal.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.content.pm.ServiceInfo;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * Foreground, one-file-at-a-time download to a user-selected SAF document.
 * Uses verified Baidu OAuth and User-Agent, follows HTTPS Baidu PCS redirects.
 * No dlink/credential is stored on disk or written to DiagnosticLog.
 * Pausing/resuming works while the foreground service process remains alive.
 */
public final class BaiduDownloadService extends Service {
    public static final String ACTION_START="com.panlocal.tool.DOWNLOAD_START";
    public static final String ACTION_PAUSE="com.panlocal.tool.DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME="com.panlocal.tool.DOWNLOAD_RESUME";
    public static final String ACTION_CANCEL="com.panlocal.tool.DOWNLOAD_CANCEL";
    private static final String CHANNEL="panlocal-downloads";
    private static final int NOTIFICATION_ID=5021;

    public static volatile String state="idle";
    public static volatile String status="尚未开始下载";
    public static volatile String displayName="";
    public static volatile long downloaded=0,total=0;

    private final Object lock=new Object();
    private volatile Thread worker;
    private volatile boolean pauseRequested=false,cancelRequested=false;
    private String fsid="",oauth="",filename="";
    private Uri target;
    private long expected=0,lastNotification=0;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"PanLocal 文件下载",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(ACTION_START.equals(action)){
            synchronized(lock){
                if(worker!=null && worker.isAlive()){
                    status="已有下载任务运行中，请先完成或取消";
                    notifyProgress();
                    return START_NOT_STICKY;
                }
                String fid=intent.getStringExtra("fsid");
                String key=intent.getStringExtra("token");
                String location=intent.getStringExtra("uri");
                String name=intent.getStringExtra("filename");
                long size=intent.getLongExtra("size",0L);
                if(fid==null||!fid.matches("[0-9]{1,22}")||key==null||key.length()<10
                        ||location==null||size<=0||name==null){
                    status="下载任务参数不完整";state="error";stopSelf();return START_NOT_STICKY;
                }
                fsid=fid;oauth=key;filename=name;target=Uri.parse(location);expected=size;
                downloaded=0;total=size;displayName=name;
                pauseRequested=false;cancelRequested=false;
                state="running";status="准备下载…";
                startAsForeground();
                runWorker();
            }
        }else if(ACTION_PAUSE.equals(action)){
            pauseRequested=true;
            if("running".equals(state)){state="pausing";status="正在暂停…";notifyProgress();}
        }else if(ACTION_RESUME.equals(action)){
            synchronized(lock){
                if("paused".equals(state) && (worker==null||!worker.isAlive())){
                    pauseRequested=false;cancelRequested=false;
                    state="running";status="正在继续下载…";
                    startAsForeground();runWorker();
                }
            }
        }else if(ACTION_CANCEL.equals(action)){
            cancelRequested=true;pauseRequested=true;
            state="cancelled";status="已停止；文件可能保留已下载的部分";
            notifyProgress();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
        return START_NOT_STICKY;
    }
    private PendingIntent action(String act,int req){
        Intent i=new Intent(this,BaiduDownloadService.class).setAction(act);
        return PendingIntent.getService(this,req,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(){
        int percent=(total>0)?(int)Math.min(100,downloaded*100/total):0;
        String title=displayName.isEmpty()?"PanLocal 下载":displayName;
        Notification.Builder builder=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setOngoing("running".equals(state)||"pausing".equals(state))
            .setProgress(100,percent,total<=0)
            .setContentIntent(PendingIntent.getActivity(this,30,
                new Intent(this,LocalDriveActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        if("running".equals(state)||"pausing".equals(state))
            builder.addAction(new Notification.Action.Builder(null,"暂停",action(ACTION_PAUSE,31)).build());
        if("paused".equals(state))
            builder.addAction(new Notification.Action.Builder(null,"继续",action(ACTION_RESUME,32)).build());
        if(!"complete".equals(state)&&!"cancelled".equals(state)&&!"error".equals(state))
            builder.addAction(new Notification.Action.Builder(null,"停止",action(ACTION_CANCEL,33)).build());
        return builder.build();
    }
    private void startAsForeground(){
        if(Build.VERSION.SDK_INT>=29){
            startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }else startForeground(NOTIFICATION_ID,notification());
    }
    private void notifyProgress(){
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastNotification<900 && "running".equals(state))return;
        lastNotification=now;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification());
    }
    private void runWorker(){
        worker=new Thread(this::execute,"panlocal-download");
        worker.start();
    }
    private static String encode(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private static boolean trusted(URL url){
        if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||
            url.getRef()!=null||(url.getPort()!=-1 && url.getPort()!=443))return false;
        String host=url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("d.pcs.baidu.com")||host.endsWith(".pcs.baidu.com")
            ||host.equals("baidupcs.com")||host.endsWith(".baidupcs.com");
    }
    private String freshDlink()throws Exception {
        URL url=new URL("https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas"
            +"&fsids="+encode("["+fsid+"]")+"&dlink=1&access_token="+encode(oauth));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12000);c.setReadTimeout(18000);
        try {
            int status=c.getResponseCode();
            if(status!=200)throw new Exception("filemetas HTTP "+status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){
                    if(out.size()+n>100000)throw new Exception("filemetas_response_too_large");
                    out.write(buffer,0,n);
                }
                JSONObject json=new JSONObject(out.toString("UTF-8"));
                int errno=json.optInt("errno",-999);
                if(errno!=0)throw new Exception("filemetas errno="+errno);
                JSONObject file=json.optJSONArray("list")==null?null:json.optJSONArray("list").optJSONObject(0);
                String link=file==null?"":file.optString("dlink","");
                if(link.isEmpty()||!trusted(new URL(link)))throw new Exception("not_trusted_dlink");
                return link;
            }
        }finally{c.disconnect();}
    }
    private static String errorResponse(InputStream stream){
        if(stream==null)return "没有错误正文";
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>4096)break;out.write(buffer,0,n);}
            JSONObject json=new JSONObject(out.toString("UTF-8"));
            return "error_code="+json.optInt("error_code",-999);
        }catch(Exception ignored){return "未识别的错误正文";}
    }
    private void execute(){
        try(ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(target,"rw")){
            if(pfd==null)throw new Exception("无法打开手机文件");
            try(FileOutputStream out=new FileOutputStream(pfd.getFileDescriptor())){
                FileChannel file=out.getChannel();
                long offset=file.size();
                if(offset>expected)throw new Exception("本地文件长度异常");
                downloaded=offset;
                if(offset==expected){complete();return;}
                int attempt=0;
                while(!cancelRequested && !pauseRequested && downloaded<expected){
                    try{
                        long beforeConnection=downloaded;
                        String link=freshDlink();
                        String sep=link.contains("?")?"&":"?";
                        URL current=new URL(link+sep+"access_token="+encode(oauth));
                        if(!trusted(current))throw new Exception("下载域名不可信");
                        int redirect=0;
                        while(redirect<5 && !pauseRequested && !cancelRequested){
                            if(!trusted(current))throw new Exception("下载跳转域名不可信");
                            HttpURLConnection con=(HttpURLConnection)current.openConnection();
                            con.setInstanceFollowRedirects(false);
                            con.setConnectTimeout(15000);con.setReadTimeout(30000);
                            con.setRequestMethod("GET");
                            con.setRequestProperty("User-Agent","pan.baidu.com");
                            con.setRequestProperty("Range","bytes="+downloaded+"-");
                            con.setRequestProperty("Accept","*/*");
                            try{
                                int http=con.getResponseCode();
                                if(http==301||http==302||http==303||http==307||http==308){
                                    String dest=con.getHeaderField("Location");
                                    if(dest==null)throw new Exception("下载重定向缺少地址");
                                    URL next=new URL(current,dest);
                                    if(!trusted(next))throw new Exception("拦截未知 CDN 跳转");
                                    current=next;redirect++;continue;
                                }
                                if(http!=206 && !(http==200 && downloaded==0)){
                                    throw new Exception("HTTP "+http+" "+errorResponse(con.getErrorStream()));
                                }
                                String range=con.getHeaderField("Content-Range");
                                final long allowedEnd;
                                try{
                                    allowedEnd=DownloadRangeValidator.validate(
                                        http,downloaded,expected,range,con.getContentLengthLong());
                                }catch(IllegalArgumentException invalidRange){
                                    DiagnosticLog.event("DOWNLOAD","rangeRejected reason="+invalidRange.getMessage());
                                    throw new Exception("服务器分段长度或偏移不一致，已停止以保护文件");
                                }
                                DiagnosticLog.event("DOWNLOAD","rangeValidated http="+http+
                                    " offset="+downloaded+" length="+(allowedEnd-downloaded));
                                String contentType=con.getContentType();
                                if(contentType!=null && (contentType.contains("json")||contentType.contains("text")||contentType.contains("html")))
                                    throw new Exception("服务器返回错误内容，不是文件");
                                file.position(downloaded);
                                try(InputStream input=con.getInputStream()){
                                    byte[] buffer=new byte[128*1024];int n;
                                    long lastUpdate=0;
                                    while(!pauseRequested && !cancelRequested && (n=input.read(buffer))!=-1){
                                        if(n==0)continue;
                                        if(downloaded+n>allowedEnd)
                                            throw new Exception("服务器发送的数据超过 Content-Range 声明");
                                        if(downloaded+n>expected)
                                            throw new Exception("服务器数据超出文件预期大小");
                                        out.write(buffer,0,n);
                                        downloaded+=n;
                                        long now=android.os.SystemClock.elapsedRealtime();
                                        if(now-lastUpdate>900){
                                            lastUpdate=now;
                                            status=String.format(Locale.US,"%.1f / %.1f MiB (%d%%)",
                                                downloaded/1048576d,expected/1048576d,
                                                (int)(downloaded*100/expected));
                                            notifyProgress();
                                        }
                                    }
                                }
                                if(pauseRequested||cancelRequested)break;
                                if(downloaded==expected){
                                    out.flush();
                                    complete();
                                    return;
                                }
                                // A partial EOF is not success; reconnect with Range.
                                break;
                            }finally{con.disconnect();}
                        }
                        if(redirect>=5)throw new Exception("重定向过多");
                        if(downloaded==beforeConnection&&!pauseRequested&&!cancelRequested)
                            throw new Exception("服务器未返回任何文件数据");
                        attempt=0;
                    }catch(Exception network){
                        if(pauseRequested||cancelRequested)break;
                        DiagnosticLog.event("DOWNLOAD","retry attempt="+(attempt+1)+" errorType="+network.getClass().getSimpleName());
                        if(++attempt>=3)throw network;
                        status="连接中断，尝试继续（"+attempt+"/3）";notifyProgress();
                        Thread.sleep(1500L*attempt);
                    }
                }
                out.flush();
                if(cancelRequested){
                    state="cancelled";status="已取消；部分文件保留";
                    notifyProgress();
                }else if(pauseRequested){
                    state="paused";status="已暂停（"+String.format(Locale.US,"%.1f MiB",downloaded/1048576d)+"）";
                    notifyProgress();
                }
            }
        }catch(Exception e){
            if(!cancelRequested){
                state="error";
                status="下载中断："+e.getMessage();
                DiagnosticLog.event("DOWNLOAD","failed exceptionType="+e.getClass().getSimpleName());
                notifyProgress();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }
    private void complete(){
        state="complete";status="下载完成，已保存到你选择的文件夹";
        DiagnosticLog.event("DOWNLOAD","completed bytes="+downloaded);
        NotificationManager manager=getSystemService(NotificationManager.class);
        stopForeground(STOP_FOREGROUND_REMOVE);
        manager.notify(NOTIFICATION_ID,notification());
        stopSelf();
    }
    @Override public void onDestroy(){
        cancelRequested=true;
        super.onDestroy();
    }
}
