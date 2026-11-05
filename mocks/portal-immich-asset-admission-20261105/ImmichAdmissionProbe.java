/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
public final class ImmichAdmissionProbe {
 public static void main(String[] args) throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("owned APK and DEX directory required");
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,ImmichAdmissionProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.ImmichSource");
  if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected class origin");
  Method parse=type.getDeclaredMethod("assetsFrom",JSONArray.class,int.class,boolean.class,java.util.Set.class);parse.setAccessible(true);
  Object instance=type.getField("INSTANCE").get(null);
  String[] names={"null-id","numeric-id","blank-ids","missing-id","nonobject-row","included-video-dedup-cap","excluded-video","zero-cap"};
  String[] rows={
   "[{\"type\":\"IMAGE\",\"id\":null},{\"type\":\"IMAGE\",\"id\":\"a\"},{\"type\":\"IMAGE\",\"id\":\"b\"}]",
   "[{\"type\":\"IMAGE\",\"id\":7},{\"type\":\"IMAGE\",\"id\":\"a\"}]",
   "[{\"type\":\"IMAGE\",\"id\":\"\"},{\"type\":\"IMAGE\",\"id\":\"  \"},{\"type\":\"IMAGE\",\"id\":\"a\"},{\"type\":\"IMAGE\",\"id\":\"b\"}]",
   "[{\"type\":\"IMAGE\",\"id\":\"a\"},{\"type\":\"IMAGE\"},{\"type\":\"IMAGE\",\"id\":\"b\"}]",
   "[{\"type\":\"IMAGE\",\"id\":\"a\"},null,8,\"bad-row\",{\"type\":\"IMAGE\",\"id\":\"b\"}]",
   "[{\"type\":\"VIDEO\",\"id\":\"clip\"},{\"type\":\"VIDEO\",\"id\":\"clip\"},{\"type\":\"IMAGE\",\"id\":\"a\"},{\"type\":\"IMAGE\",\"id\":\"b\"}]",
   "[{\"type\":\"VIDEO\",\"id\":\"clip\"},{\"type\":\"IMAGE\",\"id\":\"a\"},{\"type\":\"IMAGE\",\"id\":\"b\"}]",
   "[null,{\"type\":\"IMAGE\",\"id\":\"a\"}]"};
  String[] expected={"[[\"a\",false],[\"b\",false]]","[[\"a\",false]]","[[\"a\",false],[\"b\",false]]","[[\"a\",false],[\"b\",false]]","[[\"a\",false],[\"b\",false]]","[[\"clip\",true],[\"a\",false]]","[[\"a\",false],[\"b\",false]]","[]"};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<rows.length;i++){
   JSONObject c=new JSONObject().put("name",names[i]).put("expected",new JSONArray(expected[i]));JSONArray actual=new JSONArray();HashSet<String> seen=new HashSet<>();String failure=null;
   try{List<?> result=(List<?>)parse.invoke(instance,new JSONArray(rows[i]),i==7?0:2,i==5,seen);for(Object pair:result)actual.put(new JSONArray().put(pair.getClass().getMethod("getFirst").invoke(pair)).put(pair.getClass().getMethod("getSecond").invoke(pair)));}
   catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
   boolean ok=failure==null&&actual.toString().equals(expected[i]);if(ok)passed++;c.put("actual",actual).put("failure",failure==null?JSONObject.NULL:failure).put("seen",new JSONArray(seen)).put("passed",ok);cases.put(c);
  }
  System.out.println(new JSONObject().put("caseCount",rows.length).put("passed",passed).put("sourceClassLoaderVerified",true).put("api",android.os.Build.VERSION.SDK_INT).put("cases",cases).toString());
 }
}
