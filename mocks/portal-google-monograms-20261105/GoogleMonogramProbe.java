/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
public final class GoogleMonogramProbe {
 public static void main(String[] args) throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("owned APK and DEX directory required");
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,GoogleMonogramProbe.class.getClassLoader());
  Class<?> type=loader.loadClass("com.immortal.launcher.RemoteAlbum");
  if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected class origin");
  Method filter=null;for(Method m:type.getDeclaredMethods())if(m.getName().startsWith("isGoogleAvatarUrl")&&m.getParameterTypes().length==1)filter=m;
  if(filter==null)throw new IllegalStateException("Missing actual classifier");filter.setAccessible(true);
  String[] names={"contributor","contributor-extra-modifier","real-cropped-photo","modifier-prefix-control","owner-avatar","real-album-photo"};
  String[] urls={"https://lh3.googleusercontent.com/GeneratedContributor=s40-c-mo","https://lh3.googleusercontent.com/pw/GeneratedContributor=s512-c-mo-rw","https://lh3.googleusercontent.com/GeneratedAlbumPhoto=s512-c","https://lh3.googleusercontent.com/GeneratedAlbumPhoto=s512-c-more","https://lh3.googleusercontent.com/a/GeneratedOwner=s64-c-mo","https://lh3.googleusercontent.com/pw/GeneratedPhoto=w1920-h1080-no"};
  boolean[] expected={true,true,false,false,true,false};JSONArray cases=new JSONArray();int passed=0;
  Object instance=type.getField("INSTANCE").get(null);
  for(int i=0;i<urls.length;i++){boolean actual=(Boolean)filter.invoke(instance,urls[i]);boolean ok=actual==expected[i];if(ok)passed++;cases.put(new JSONObject().put("name",names[i]).put("url",urls[i]).put("expected",expected[i]).put("actual",actual).put("passed",ok));}
  System.out.println(new JSONObject().put("caseCount",urls.length).put("passed",passed).put("sourceClassLoaderVerified",true).put("api",android.os.Build.VERSION.SDK_INT).put("cases",cases).toString());
 }
}
