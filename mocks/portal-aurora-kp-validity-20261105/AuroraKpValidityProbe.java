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
public final class AuroraKpValidityProbe {
 private static int deniedUrlOpens;
 private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
 private static Object safe(Double value){if(value==null)return JSONObject.NULL;if(Double.isNaN(value))return "NaN";if(Double.isInfinite(value))return value>0?"Infinity":"-Infinity";return value;}
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned debug APK and owned DEX directory required");
  URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
   protected URLConnection openConnection(URL u)throws IOException{deniedUrlOpens++;throw new IOException("Generated Kp getter probe denied all URL opens");}
   protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
  });
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,AuroraKpValidityProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.Aurora");
  if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
  Object instance=type.getField("INSTANCE").get(null);Method getter=null;int candidates=0;
  for(Method m:type.getDeclaredMethods()){
   Class<?>[] parameters=m.getParameterTypes();String name=m.getName();
   if((name.equals("kpOf")||name.startsWith("kpOf$"))&&parameters.length==1&&parameters[0]==Object.class&&m.getReturnType()==Double.class){getter=m;candidates++;}
  }
  if(candidates!=1)throw new IllegalStateException("Expected exactly one actual Object->Double Kp getter, got "+candidates);
  getter.setAccessible(true);
  String[] names={"object-uppercase-infinity-rejected","array-NaN-rejected","array-infinity-rejected","object-lowercase-negative-rejected","object-uppercase-over-nine-rejected","object-zero-control","array-string-nine-control","lowercase-string-fraction-control"};
  String[] inputs={"{\"Kp\":\"Infinity\"}","[\"generated\",\"NaN\"]","[\"generated\",\"Infinity\"]","{\"kp\":-0.5}","{\"Kp\":9.5}","{\"Kp\":0}","[\"generated\",\"9\"]","{\"kp\":\"3.67\"}"};
  Double[] expected={null,null,null,null,null,0.0,9.0,3.67};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<names.length;i++){
   int before=deniedUrlOpens;Object row=inputs[i].startsWith("[")?new JSONArray(inputs[i]):new JSONObject(inputs[i]);Double actual=null;String failure=null;
   try{actual=(Double)getter.invoke(instance,row);}
   catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
   boolean literal=failure==null&&(expected[i]==null?actual==null:actual!=null&&finite(actual)&&Double.compare(actual,expected[i])==0);
   boolean noUrl=deniedUrlOpens==before;boolean ok=literal&&noUrl;if(ok)passed++;
   cases.put(new JSONObject().put("name",names[i]).put("generatedJson",inputs[i]).put("rowShape",row instanceof JSONArray?"frameworkJSONArray":"frameworkJSONObject").put("expected",safe(expected[i])).put("expectedIsNull",expected[i]==null).put("actual",safe(actual)).put("actualIsNull",actual==null).put("actualFinite",actual==null?JSONObject.NULL:finite(actual)).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("urlOpens",deniedUrlOpens-before).put("passed",ok));
  }
  System.out.println(new JSONObject().put("caseCount",8).put("passed",passed).put("sourceClassLoaderVerified",true).put("actualGetterMethod",getter.getName()).put("frameworkJSONObjectClassLoader",String.valueOf(JSONObject.class.getClassLoader())).put("frameworkJSONArrayClassLoader",String.valueOf(JSONArray.class.getClassLoader())).put("api",android.os.Build.VERSION.SDK_INT).put("deniedUrlOpens",deniedUrlOpens).put("actualNetworkRequests",0).put("scope","Actual APK Aurora.kpOf(Object), generated framework JSON; no HTTP/cache/classification/outlook/Context/provider/preferences").put("cases",cases).toString());
 }
}
