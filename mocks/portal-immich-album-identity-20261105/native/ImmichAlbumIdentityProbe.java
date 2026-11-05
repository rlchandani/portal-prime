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
public final class ImmichAlbumIdentityProbe {
 private static final String ENDPOINT="http://fixture.invalid/api/albums";
 private static String body;private static int status;private static int denied;
 private static final List<MemoryConnection> opened=new ArrayList<>();
 private static final class MemoryConnection extends HttpURLConnection {
  final byte[] bytes;final int code;final Map<String,String> headers=new TreeMap<>();
  int disconnects,responseReads,inputReads,inputCloses,errorReads,errorCloses,connectCalls;
  MemoryConnection(URL u)throws IOException{super(u);bytes=body.getBytes("UTF-8");code=status;}
  public void connect(){connectCalls++;connected=true;}
  public void disconnect(){disconnects++;connected=false;}
  public boolean usingProxy(){return false;}
  public void setRequestProperty(String key,String value){headers.put(key,value);super.setRequestProperty(key,value);}
  public int getResponseCode(){responseReads++;return code;}
  public long getContentLengthLong(){return bytes.length;}
  public int getContentLength(){return bytes.length;}
  public String getHeaderField(String name){return null;}
  public InputStream getInputStream(){inputReads++;return new ByteArrayInputStream(bytes){public void close()throws IOException{inputCloses++;super.close();}};}
  public InputStream getErrorStream(){errorReads++;return new ByteArrayInputStream(new byte[0]){public void close()throws IOException{errorCloses++;super.close();}};}
  JSONObject observation()throws JSONException{return new JSONObject().put("url",url.toExternalForm()).put("method",getRequestMethod()).put("headers",new JSONObject(headers)).put("status",code).put("disconnects",disconnects).put("responseReads",responseReads).put("inputReads",inputReads).put("inputCloses",inputCloses).put("errorReads",errorReads).put("errorCloses",errorCloses).put("connectCalls",connectCalls).put("inMemoryOnly",true);}
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned APK and DEX directory required");
  // Process-global factory is intentionally one-shot. Never run inside an owner app process.
  URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
   protected URLConnection openConnection(URL u)throws IOException{
    if(!ENDPOINT.equals(u.toExternalForm())){denied++;throw new IOException("Fixture denied URL");}
    MemoryConnection c=new MemoryConnection(u);opened.add(c);return c;
   }
   protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
  });
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,ImmichAlbumIdentityProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.ImmichSource");
  if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
  Object instance=type.getField("INSTANCE").get(null);
  Method list=type.getMethod("listAlbums",String.class,String.class);
  String[] names={"nonobject-between-neighbors","missing-null-nonstring-ids","blank-ids-between-neighbors","duplicate-first-details-stable-order","all-invalid-empty","unicode-default-metadata","malformed-top-level-null","http503-null"};
  String[] bodies={
   "[{\"id\":\"a\",\"albumName\":\"First\",\"assetCount\":1},null,7,\"row\",{\"id\":\"b\",\"albumName\":\"Last\",\"assetCount\":2}]",
   "[{\"id\":\"a\",\"albumName\":\"First\",\"assetCount\":1},{},{\"id\":null},{\"id\":7},{\"id\":true},{\"id\":[]},{\"id\":{}},{\"id\":\"b\",\"albumName\":\"Last\",\"assetCount\":2}]",
   "[{\"id\":\"a\",\"albumName\":\"First\",\"assetCount\":1},{\"id\":\"\"},{\"id\":\" \\t\\n \"},{\"id\":\"b\",\"albumName\":\"Last\",\"assetCount\":2}]",
   "[{\"id\":\"a\",\"albumName\":\"First\",\"assetCount\":1},{\"id\":\"a\",\"albumName\":\"Duplicate\",\"assetCount\":99},{\"id\":\"b\",\"albumName\":\"Last\",\"assetCount\":2},{\"id\":\"a\",\"albumName\":\"Again\",\"assetCount\":55}]",
   "[null,7,\"bad\",{}, {\"id\":null},{\"id\":9},{\"id\":\"\"},{\"id\":\"   \"}]",
   "[{\"id\":\"アルバム-é\",\"albumName\":\"Nóinín 🌿\",\"assetCount\":4},{\"id\":\"b\"}]",
   "{\"albums\":[]}","[]"};
  String neighbors="[[\"a\",\"First\",1],[\"b\",\"Last\",2]]";
  String[] expected={neighbors,neighbors,neighbors,neighbors,"[]","[[\"アルバム-é\",\"Nóinín 🌿\",4],[\"b\",\"Album\",0]]",null,null};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<names.length;i++){
   body=bodies[i];status=i==7?503:200;opened.clear();int deniedBefore=denied;
   Object result=null;String failure=null;JSONArray actual=null;
   try{result=list.invoke(instance,"http://fixture.invalid"," generated-album-key ");
    if(result!=null){actual=new JSONArray();for(Object album:(List<?>)result){Class<?> a=album.getClass();actual.put(new JSONArray().put(a.getMethod("getId").invoke(album)).put(a.getMethod("getName").invoke(album)).put(a.getMethod("getCount").invoke(album)));}}
   }catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
   boolean literal=failure==null&&(expected[i]==null?actual==null:actual!=null&&actual.toString().equals(new JSONArray(expected[i]).toString()));
   boolean transport=opened.size()==1&&denied==deniedBefore;JSONArray requests=new JSONArray();
   for(MemoryConnection c:opened){requests.put(c.observation());transport=transport&&c.disconnects==1&&"GET".equals(c.getRequestMethod())&&"generated-album-key".equals(c.headers.get("x-api-key"))&&c.headers.size()==1&&c.inputCloses==c.inputReads&&c.errorCloses==c.errorReads;}
   boolean ok=literal&&transport;if(ok)passed++;
   cases.put(new JSONObject().put("name",names[i]).put("body",bodies[i]).put("expected",expected[i]==null?JSONObject.NULL:new JSONArray(expected[i])).put("actual",actual==null?JSONObject.NULL:actual).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("transportPassed",transport).put("passed",ok).put("openCount",opened.size()).put("deniedUnexpected",denied-deniedBefore).put("requests",requests));
  }
  System.out.println(new JSONObject().put("caseCount",8).put("passed",passed).put("sourceClassLoaderVerified",true).put("entrypoint","ImmichSource.INSTANCE.listAlbums(String,String)").put("api",android.os.Build.VERSION.SDK_INT).put("actualNetworkRequests",0).put("networkBoundary","Process-only URL factory never delegates; exact generated endpoint only; in-memory HttpURLConnection").put("deniedUnexpected",denied).put("cases",cases).toString());
 }
}
