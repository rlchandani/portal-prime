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
public final class StoreCatalogNullNumericProbe {
 private static int deniedOpens;
 private static String app(String name,String pkg,String extras){return "{\"name\":\""+name+"\",\"packageName\":\""+pkg+"\""+extras+"}";}
 private static String catalog(String...apps){return "{\"schemaVersion\":2,\"categories\":[{\"name\":\"Generated\",\"apps\":["+String.join(",",apps)+"]}]}";}
 private static JSONArray row(String name,String pkg,Object minSdk,Object version)throws JSONException{return new JSONArray().put(name).put(pkg).put(minSdk==null?JSONObject.NULL:minSdk).put(version==null?JSONObject.NULL:version);}
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned APK and owned DEX directory required");
  // Fresh process only. No URL request can delegate to a networking implementation.
  URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
   protected URLConnection openConnection(URL u)throws IOException{deniedOpens++;throw new IOException("Generated parser probe denied all URL opens");}
   protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
  });
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,StoreCatalogNullNumericProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.StoreCatalog");
  if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
  Object instance=type.getField("INSTANCE").get(null);
  Method parse=null;int candidates=0;
  for(Method m:type.getDeclaredMethods()){
   Class<?>[] parameters=m.getParameterTypes();String name=m.getName();
   if((name.equals("parse")||name.startsWith("parse$"))&&parameters.length==1&&parameters[0]==String.class&&List.class.isAssignableFrom(m.getReturnType())){parse=m;candidates++;}
  }
  if(candidates!=1)throw new IllegalStateException("Expected exactly one actual single-String catalog parse method, got "+candidates);
  parse.setAccessible(true);
  String[] names={"explicit-null-minSdk","explicit-null-versionCode","both-null-valid-neighbors-order","absent-numeric-fields","actual-integer-numeric-values","invalid-non-null-numeric-text-rejected"};
  String[] inputs={
   catalog(app("NullMin","generated.nullmin",",\"minSdk\":null")),
   catalog(app("NullVersion","generated.nullversion",",\"versionCode\":null")),
   catalog(app("Before","generated.before",",\"minSdk\":24,\"versionCode\":5"),app("BothNull","generated.both",",\"minSdk\":null,\"versionCode\":null"),app("After","generated.after",",\"minSdk\":29,\"versionCode\":9")),
   catalog(app("Absent","generated.absent","")),
   catalog(app("Integer","generated.integer",",\"minSdk\":29,\"versionCode\":4294967297")),
   catalog(app("InvalidText","generated.invalid",",\"minSdk\":\"twenty-nine\",\"versionCode\":\"latest\""))};
  JSONArray[] expected={new JSONArray().put(row("NullMin","generated.nullmin",null,null)),new JSONArray().put(row("NullVersion","generated.nullversion",null,null)),new JSONArray().put(row("Before","generated.before",24,5L)).put(row("BothNull","generated.both",null,null)).put(row("After","generated.after",29,9L)),new JSONArray().put(row("Absent","generated.absent",null,null)),new JSONArray().put(row("Integer","generated.integer",29,4294967297L)),null};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<names.length;i++){
   int before=deniedOpens;JSONArray actual=null;String failure=null;
   try{Object result=parse.invoke(instance,inputs[i]);actual=new JSONArray();for(Object item:(List<?>)result){Class<?> c=item.getClass();if(c.getClassLoader()!=loader)throw new IllegalStateException("Unexpected parsed app class origin");actual.put(new JSONArray().put(c.getMethod("getName").invoke(item)).put(c.getMethod("getPackageName").invoke(item)).put(nullable(c.getMethod("getMinSdk").invoke(item))).put(nullable(c.getMethod("getVersionCode").invoke(item))));}}
   catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
   boolean rejected=i==5;boolean literal=rejected?"org.json.JSONException".equals(failure):failure==null&&actual!=null&&actual.toString().equals(expected[i].toString());
   boolean noUrl=deniedOpens==before;boolean ok=literal&&noUrl;if(ok)passed++;
   cases.put(new JSONObject().put("name",names[i]).put("generatedJson",inputs[i]).put("expectedRejected",rejected).put("expected",expected[i]==null?JSONObject.NULL:expected[i]).put("actual",actual==null?JSONObject.NULL:actual).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("urlOpens",deniedOpens-before).put("passed",ok));
  }
  System.out.println(new JSONObject().put("caseCount",6).put("passed",passed).put("sourceClassLoaderVerified",true).put("actualParseMethod",parse.getName()).put("api",android.os.Build.VERSION.SDK_INT).put("deniedUrlOpens",deniedOpens).put("actualNetworkRequests",0).put("scope","Actual packaged StoreCatalog.parse with generated strings only; no Context, assets, UI, download or install call").put("cases",cases).toString());
 }
 private static Object nullable(Object value){return value==null?JSONObject.NULL:value;}
}
