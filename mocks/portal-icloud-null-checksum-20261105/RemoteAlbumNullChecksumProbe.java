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
import org.json.*;
public final class RemoteAlbumNullChecksumProbe {
 private static int deniedOpens;
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned debug APK and owned DEX directory required");
  // One-shot factory in an isolated fresh process; no provider request can delegate.
  URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
   protected URLConnection openConnection(URL u)throws IOException{deniedOpens++;throw new IOException("Generated derivative probe denied all URL opens");}
   protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
  });
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,RemoteAlbumNullChecksumProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.RemoteAlbum");
  if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
  Object instance=type.getField("INSTANCE").get(null);
  Method select=null;int candidates=0;
  for(Method m:type.getDeclaredMethods()){
   Class<?>[] p=m.getParameterTypes();String name=m.getName();
   if((name.equals("pickBestDerivative")||name.startsWith("pickBestDerivative$"))&&p.length==3&&p[0]==JSONObject.class&&p[1]==int.class&&p[2]==int.class&&m.getReturnType()==String.class){select=m;candidates++;}
  }
  if(candidates!=1)throw new IllegalStateException("Expected exactly one actual JSONObject/int/int derivative selector, got "+candidates);
  select.setAccessible(true);
  String[] names={"larger-null-checksum-smaller-good","all-null-checksums","literal-null-string-accepted","absent-large-checksum-smaller-good","normal-covering-selection"};
  String[] inputs={
   "{\"1024\":{\"checksum\":\"good\"},\"2048\":{\"checksum\":null}}",
   "{\"1024\":{\"checksum\":null},\"2048\":{\"checksum\":null}}",
   "{\"1024\":{\"checksum\":\"small\"},\"2048\":{\"checksum\":\"null\"}}",
   "{\"1024\":{\"checksum\":\"good\"},\"2048\":{}}",
   "{\"1024\":{\"checksum\":\"small\"},\"2048\":{\"checksum\":\"large\"}}"};
  String[] expected={"good",null,"null","good","large"};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<names.length;i++){
   int before=deniedOpens;String actual=null,failure=null;JSONObject generated=new JSONObject(inputs[i]);
   try{actual=(String)select.invoke(instance,generated,1920,1080);}
   catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
   boolean literal=failure==null&&(expected[i]==null?actual==null:expected[i].equals(actual));
   boolean noUrl=deniedOpens==before;boolean ok=literal&&noUrl;if(ok)passed++;
   cases.put(new JSONObject().put("name",names[i]).put("generatedJson",inputs[i]).put("screenW",1920).put("screenH",1080).put("expected",expected[i]==null?JSONObject.NULL:expected[i]).put("expectedIsNull",expected[i]==null).put("actual",actual==null?JSONObject.NULL:actual).put("actualIsNull",actual==null).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("urlOpens",deniedOpens-before).put("passed",ok));
  }
  System.out.println(new JSONObject().put("caseCount",5).put("passed",passed).put("sourceClassLoaderVerified",true).put("actualSelectorMethod",select.getName()).put("frameworkJSONObjectClass",JSONObject.class.getName()).put("frameworkJSONObjectClassLoader",String.valueOf(JSONObject.class.getClassLoader())).put("api",android.os.Build.VERSION.SDK_INT).put("deniedUrlOpens",deniedOpens).put("actualNetworkRequests",0).put("scope","Actual packaged RemoteAlbum.pickBestDerivative; generated framework JSON; no fetch/provider/Context/preferences").put("cases",cases).toString());
 }
}
