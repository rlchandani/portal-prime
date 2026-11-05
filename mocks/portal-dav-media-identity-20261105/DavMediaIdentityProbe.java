/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import dalvik.system.DexClassLoader;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
public final class DavMediaIdentityProbe {
 private static final String ROOT="http://generated-dav.invalid/photos/";
 private static final String CHILD=ROOT+"child/";
 private static final Map<String,String> bodies=new HashMap<>();
 private static final Map<String,Integer> statuses=new HashMap<>();
 private static final List<MemoryConnection> opened=new ArrayList<>();
 private static int denied;
 private static final class MemoryConnection extends HttpURLConnection {
  final byte[] bytes;final int code;final Map<String,String> headers=new TreeMap<>();
  int disconnects,responseReads,inputReads,inputCloses,connectCalls;
  MemoryConnection(URL u)throws IOException{super(u);bytes=bodies.get(u.toExternalForm()).getBytes("UTF-8");code=statuses.get(u.toExternalForm());}
  public void connect(){connectCalls++;connected=true;}
  public void disconnect(){disconnects++;connected=false;}
  public boolean usingProxy(){return false;}
  public void setRequestProperty(String k,String v){headers.put(k,v);super.setRequestProperty(k,v);}
  public void setRequestMethod(String value)throws ProtocolException{method=value;}
  public int getResponseCode(){responseReads++;return code;}
  public long getContentLengthLong(){return bytes.length;}
  public int getContentLength(){return bytes.length;}
  public String getHeaderField(String name){return null;}
  public InputStream getInputStream(){inputReads++;return new ByteArrayInputStream(bytes){public void close()throws IOException{inputCloses++;super.close();}};}
  public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
  JSONObject observation()throws JSONException{return new JSONObject().put("url",url.toExternalForm()).put("method",getRequestMethod()).put("headers",new JSONObject(headers)).put("status",code).put("disconnects",disconnects).put("responseReads",responseReads).put("inputReads",inputReads).put("inputCloses",inputCloses).put("connectCalls",connectCalls).put("inMemoryOnly",true);}
 }
 private static String row(String href,boolean directory){return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop><d:resourcetype>"+(directory?"<d:collection/>":"")+"</d:resourcetype></d:prop></d:propstat></d:response>";}
 private static String xml(String...rows){StringBuilder b=new StringBuilder("<d:multistatus xmlns:d=\"DAV:\">");for(String r:rows)b.append(r);return b.append("</d:multistatus>").toString();}
 private static void response(String url,int code,String body){bodies.put(url,body);statuses.put(url,code);}
 private static JSONArray expect(String...urls)throws JSONException{JSONArray out=new JSONArray();for(String url:urls)out.put(new JSONArray().put(url).put(url.endsWith(".mp4")));return out;}
 private static JSONObject invoke(Object instance,Method entry,boolean media,boolean videos,int cap,JSONArray expected)throws Exception{
  opened.clear();int before=denied;JSONArray actual=null;String failure=null;
  try{Object result=media?entry.invoke(instance,ROOT,null,null,videos,cap):entry.invoke(instance,ROOT,null,null,cap);
   if(result!=null){actual=new JSONArray();for(Object item:(List<?>)result){if(media){Class<?> c=item.getClass();actual.put(new JSONArray().put(c.getMethod("getUrl").invoke(item)).put(c.getMethod("isVideo").invoke(item)));}else actual.put(new JSONArray().put((String)item).put(false));}}
  }catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
  boolean literal=failure==null&&(expected==null?actual==null:actual!=null&&actual.toString().equals(expected.toString()));
  boolean transport=denied==before&&opened.size()>0;JSONArray requests=new JSONArray();
  for(MemoryConnection c:opened){requests.put(c.observation());transport=transport&&c.disconnects==1&&"PROPFIND".equals(c.getRequestMethod())&&"1".equals(c.headers.get("Depth"))&&"PortalPhotoFrame/1.0".equals(c.headers.get("User-Agent"))&&c.headers.size()==2&&c.inputCloses==c.inputReads;}
  return new JSONObject().put("entrypoint",media?"DavSource.INSTANCE.listMedia(String,String,String,boolean,int)":"DavSource.INSTANCE.listImageUrls(String,String,String,int)").put("includeVideo",videos).put("cap",cap).put("expected",expected==null?JSONObject.NULL:expected).put("actual",actual==null?JSONObject.NULL:actual).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("transportPassed",transport).put("passed",literal&&transport).put("deniedUnexpected",denied-before).put("requests",requests);
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned APK and owned DEX directory required");
  URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
   protected URLConnection openConnection(URL u)throws IOException{String url=u.toExternalForm();if(!bodies.containsKey(url)||(!ROOT.equals(url)&&!CHILD.equals(url))){denied++;throw new IOException("Fixture denied URL");}MemoryConnection c=new MemoryConnection(u);opened.add(c);return c;}
   protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
  });
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,DavMediaIdentityProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.DavSource");if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
  Object instance=type.getField("INSTANCE").get(null);
  Method images=type.getMethod("listImageUrls",String.class,String.class,String.class,int.class);
  Method media=type.getMethod("listMedia",String.class,String.class,String.class,boolean.class,int.class);
  String a=ROOT+"a.jpg",b=ROOT+"b.png",clip=ROOT+"clip.mp4";
  String[] names={"root-duplicate-before-later-unique","absolute-and-path-resolve-same-url","root-child-repeat-before-later-unique","included-video-duplicate","unique-order-control","same-basename-distinct-directories","excluded-videos-do-not-consume-cap","failed-root-and-empty-root-controls"};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<8;i++){
   bodies.clear();statuses.clear();boolean useMedia=i==3||i==4||i==6;boolean video=i==3;
   if(i==0)response(ROOT,207,xml(row("/photos/a.jpg",false),row("/photos/a.jpg",false),row("/photos/b.png",false)));
   if(i==1)response(ROOT,207,xml(row(a,false),row("/photos/a.jpg",false),row("/photos/b.png",false)));
   if(i==2){response(ROOT,207,xml(row("/photos/a.jpg",false),row("/photos/child/",true)));response(CHILD,207,xml(row(a,false),row("/photos/b.png",false)));}
   if(i==3)response(ROOT,207,xml(row("/photos/clip.mp4",false),row(clip,false),row("/photos/a.jpg",false)));
   if(i==4)response(ROOT,207,xml(row("/photos/a.jpg",false),row("/photos/b.png",false)));
   if(i==5)response(ROOT,207,xml(row("/photos/one/a.jpg",false),row("/photos/two/a.jpg",false)));
   if(i==6)response(ROOT,207,xml(row("/photos/clip.mp4",false),row("/photos/a.jpg",false),row("/photos/clip.mp4",false),row("/photos/b.png",false)));
   JSONObject c;
   if(i==7){response(ROOT,503,xml());JSONObject failed=invoke(instance,images,false,false,2,null);response(ROOT,207,xml());JSONObject empty=invoke(instance,images,false,false,2,new JSONArray());c=new JSONObject().put("checks",new JSONArray().put(failed).put(empty)).put("passed",failed.getBoolean("passed")&&empty.getBoolean("passed"));}
   else{JSONArray expected=i==3?expect(clip,a):i==5?expect(ROOT+"one/a.jpg",ROOT+"two/a.jpg"):expect(a,b);c=invoke(instance,useMedia?media:images,useMedia,video,2,expected);}
   c.put("name",names[i]);if(c.getBoolean("passed"))passed++;cases.put(c);
  }
  System.out.println(new JSONObject().put("caseCount",8).put("passed",passed).put("sourceClassLoaderVerified",true).put("api",android.os.Build.VERSION.SDK_INT).put("actualNetworkRequests",0).put("networkBoundary","Process-only URL factory never delegates; finite generated root/child listings only").put("deniedUnexpected",denied).put("cases",cases).toString());
 }
}
