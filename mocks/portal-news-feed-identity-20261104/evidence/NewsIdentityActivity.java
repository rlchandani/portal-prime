/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.newsidentityfixture;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.DexClassLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class NewsIdentityActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final JSONArray cases=new JSONArray(),preferenceRequests=new JSONArray();
 private final AtomicInteger unexpectedPreferenceCalls=new AtomicInteger();
 private Object config;private Method load,add,remove;private File source;private boolean closed;private int failures;
 private interface Case {void run(Context ctx,SharedPreferences prefs)throws Exception;}
 public void onCreate(Bundle b){super.onCreate(b);handler.postDelayed(()->finishReport("owner_timeout"),30000);new Thread(this::probe,"OwnedNewsIdentity").start();}
 private void probe(){try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);}
  DexClassLoader loader=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());Class<?> type=loader.loadClass("com.immortal.launcher.NewsConfig");check(type.getClassLoader()==loader,"Actual embedded APK class loader");config=type.getField("INSTANCE").get(null);load=type.getMethod("load",Context.class);add=type.getMethod("add",Context.class,String.class,String.class);remove=type.getMethod("remove",Context.class,long.class);
  runCase("missingIdsStableAcrossReadOnlyReload", "[{\"name\":\"A\",\"url\":\"https://generated.invalid/a\"},{\"id\":77,\"name\":\"B\",\"url\":\"https://generated.invalid/b\"}]", (c,p)->{List<?> a=rows(c),b=rows(c);check(a.size()==2&&b.size()==2,"Two rows");check(ids(a).equals(ids(b)),"Stable missing-row identity");check(id(a.get(1))==77,"Explicit identity retained");});
  runCase("invalidIdsStableAcrossReadOnlyReload", "[{\"id\":\"bad\",\"name\":\"A\",\"url\":\"https://generated.invalid/a\"},{\"id\":77,\"name\":\"B\",\"url\":\"https://generated.invalid/b\"}]", (c,p)->{List<?> a=rows(c),b=rows(c);check(a.size()==2&&ids(a).equals(ids(b)),"Stable invalid-row identity");});
  runCase("duplicateIdsAreDistinctAndStable", pair(77,77), (c,p)->{List<?> a=rows(c),b=rows(c);check(a.size()==2&&new HashSet<Long>(ids(a)).size()==2,"Distinct duplicate-row identities");check(ids(a).equals(ids(b)),"Stable duplicate normalization");});
  runCase("removeMissingRowLeavesOnlyOtherRow", "[{\"name\":\"A\",\"url\":\"https://generated.invalid/a\"},{\"id\":77,\"name\":\"B\",\"url\":\"https://generated.invalid/b\"}]", (c,p)->{long selected=id(rows(c).get(0));remove.invoke(config,c,selected);checkRows(rows(c),new String[]{"B"},new String[]{"https://generated.invalid/b"});});
  runCase("removeInvalidRowLeavesOnlyOtherRow", "[{\"id\":\"bad\",\"name\":\"A\",\"url\":\"https://generated.invalid/a\"},{\"id\":77,\"name\":\"B\",\"url\":\"https://generated.invalid/b\"}]", (c,p)->{long selected=id(rows(c).get(0));remove.invoke(config,c,selected);checkRows(rows(c),new String[]{"B"},new String[]{"https://generated.invalid/b"});});
  runCase("removeFirstDuplicateLeavesSecond",pair(77,77),(c,p)->{remove.invoke(config,c,id(rows(c).get(0)));checkRows(rows(c),new String[]{"B"},new String[]{"https://generated.invalid/b"});});
  runCase("removeSecondDuplicateLeavesFirst",pair(77,77),(c,p)->{remove.invoke(config,c,id(rows(c).get(1)));checkRows(rows(c),new String[]{"A"},new String[]{"https://generated.invalid/a"});});
  runCase("laterReservedUniqueIdsRemainExact","[{\"url\":\"https://generated.invalid/missing\"},{\"id\":0,\"url\":\"https://generated.invalid/zero\"},{\"id\":1,\"url\":\"https://generated.invalid/one\"},{\"id\":2,\"url\":\"https://generated.invalid/two\"}]",(c,p)->{List<?> a=rows(c);check(a.size()==4&&id(a.get(1))==0&&id(a.get(2))==1&&id(a.get(3))==2,"Reserved explicit identities");check(new HashSet<Long>(ids(a)).size()==4,"Generated identity avoids reserved IDs");});
  runCase("longBoundsAndSignedIdsRemainExact","[{\"id\":-9223372036854775808,\"url\":\"https://generated.invalid/min\"},{\"id\":9223372036854775807,\"url\":\"https://generated.invalid/max\"},{\"id\":-1,\"url\":\"https://generated.invalid/negative\"}]",(c,p)->{check(ids(rows(c)).equals(Arrays.asList(Long.MIN_VALUE,Long.MAX_VALUE,-1L)),"Long bounds and signed IDs");});
  runCase("trimmedNamesUrlsOrderAndBlankFiltering","[{\"id\":3,\"name\":\" First \",\"url\":\" https://generated.invalid/first \"},{\"id\":4,\"name\":\"ignored\",\"url\":\"   \"},{\"id\":5,\"name\":\" Second \",\"url\":\" https://generated.invalid/second \"},{\"id\":6,\"name\":\"ignored\"},{\"id\":7,\"url\":\"https://generated.invalid/third\"}]",(c,p)->checkRows(rows(c),new String[]{"First","Second",""},new String[]{"https://generated.invalid/first","https://generated.invalid/second","https://generated.invalid/third"}));
  runCase("loadNeverWritesLegacyBytesOrSentinels","[{\"id\":\"bad\",\"name\":\" A \",\"url\":\" https://generated.invalid/a \"},{\"id\":9,\"url\":\"https://generated.invalid/b\"},{\"id\":9,\"url\":\"https://generated.invalid/c\"}]",(c,p)->{String raw=p.getString("feeds",null);rows(c);rows(c);check(raw.equals(p.getString("feeds",null)),"Read-only load exact stored bytes");});
  runCase("addPersistsNormalizedIdsAndOneNewRow","[{\"name\":\"A\",\"url\":\"https://generated.invalid/a\"},{\"id\":\"bad\",\"name\":\"B\",\"url\":\"https://generated.invalid/b\"},{\"id\":9,\"name\":\"C\",\"url\":\"https://generated.invalid/c\"},{\"id\":9,\"name\":\"D\",\"url\":\"https://generated.invalid/d\"},{\"id\":9223372036854775807,\"name\":\"E\",\"url\":\"https://generated.invalid/e\"}]",(c,p)->{List<Long> prior=ids(rows(c));add.invoke(config,c," New "," https://generated.invalid/new ");List<?> after=rows(c);checkRows(after,new String[]{"A","B","C","D","E","New"},new String[]{"https://generated.invalid/a","https://generated.invalid/b","https://generated.invalid/c","https://generated.invalid/d","https://generated.invalid/e","https://generated.invalid/new"});List<Long> actual=ids(after);check(actual.subList(0,prior.size()).equals(prior),"Previously loaded normalized identities persisted");check(new HashSet<Long>(actual).size()==6,"Exactly six unique identities");JSONArray stored=new JSONArray(p.getString("feeds",null));for(int i=0;i<stored.length();i++){Object value=stored.getJSONObject(i).get("id");check(value instanceof Number&&((Number)value).longValue()==actual.get(i),"Stored numeric identity");}check(ids(rows(c)).equals(actual),"Persisted reload identities");});
  runCase("blankAddIsReadOnly","[{\"id\":5,\"url\":\"https://generated.invalid/a\"}]",(c,p)->{String raw=p.getString("feeds",null);add.invoke(config,c,"ignored","  ");check(raw.equals(p.getString("feeds",null)),"Blank add leaves exact bytes");});
  runCase("missingPreferenceReturnsEmptyReadOnly",null,(c,p)->{check(rows(c).isEmpty(),"Missing store empty");check(!p.contains("feeds"),"Missing key not written");});
  runCase("invalidArrayReturnsEmptyReadOnly","not a JSON array",(c,p)->{check(rows(c).isEmpty(),"Malformed array empty");check(p.getString("feeds",null).equals("not a JSON array"),"Malformed store unchanged");});
  runCase("removeUnmatchedIdRetainsBothRows",pair(11,12),(c,p)->{remove.invoke(config,c,Long.MIN_VALUE);checkRows(rows(c),new String[]{"A","B"},new String[]{"https://generated.invalid/a","https://generated.invalid/b"});check(ids(rows(c)).equals(Arrays.asList(11L,12L)),"Unmatched remove preserves IDs");});
  finishReport(null);
 }catch(Throwable t){finishReport("setup:"+describe(t));}}
 private void runCase(String name,String raw,Case body)throws Exception{
  SharedPreferences prefs=NewsIdentityActivity.this.getSharedPreferences("generated-"+name,MODE_PRIVATE);SharedPreferences.Editor seed=prefs.edit().clear().putString("sentinel","fixture-only-unchanged").putInt("sentinelNumber",314159);if(raw!=null)seed.putString("feeds",raw);check(seed.commit(),"Owned preference seed committed");
  Context isolated=new ContextWrapper(this){public SharedPreferences getSharedPreferences(String requested,int mode){if(!requested.equals("immortal_news")||mode!=MODE_PRIVATE){unexpectedPreferenceCalls.incrementAndGet();throw new IllegalStateException("Unexpected preference request");}synchronized(preferenceRequests){try{preferenceRequests.put(new JSONObject().put("case",name).put("name",requested).put("mode",mode));}catch(Exception ignored){}}return prefs;}public Context getApplicationContext(){return this;}};
  String error=null;try{body.run(isolated,prefs);}catch(Throwable t){error=describe(t);}boolean sentinel=prefs.getString("sentinel","").equals("fixture-only-unchanged")&&prefs.getInt("sentinelNumber",0)==314159;if(!sentinel)error=(error==null?"":error+"; ")+"Sentinel mutated";boolean pass=error==null;if(!pass)failures++;cases.put(new JSONObject().put("name",name).put("passed",pass).put("error",error==null?JSONObject.NULL:error).put("sentinelUnchanged",sentinel).put("fixturePreferenceStore","generated-"+name));
 }
 private List<?> rows(Context c)throws Exception{return (List<?>)load.invoke(config,c);}
 private long id(Object row)throws Exception{return ((Number)row.getClass().getMethod("getId").invoke(row)).longValue();}
 private List<Long> ids(List<?> rows)throws Exception{List<Long> result=new ArrayList<>();for(Object row:rows)result.add(id(row));return result;}
 private void checkRows(List<?> values,String[] names,String[] urls)throws Exception{check(values.size()==names.length,"Expected row count");for(int i=0;i<names.length;i++){Object row=values.get(i);check(row.getClass().getMethod("getName").invoke(row).equals(names[i]),"Name/order row "+i);check(row.getClass().getMethod("getUrl").invoke(row).equals(urls[i]),"URL/order row "+i);}}
 private static String pair(long a,long b){return "[{\"id\":"+a+",\"name\":\"A\",\"url\":\"https://generated.invalid/a\"},{\"id\":"+b+",\"name\":\"B\",\"url\":\"https://generated.invalid/b\"}]";}
 private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 private static String describe(Throwable t){if(t instanceof InvocationTargetException&&((InvocationTargetException)t).getCause()!=null)t=((InvocationTargetException)t).getCause();return t.getClass().getName()+":"+String.valueOf(t.getMessage());}
 private static String digest(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder result=new StringBuilder();for(byte b:md.digest())result.append(String.format(Locale.US,"%02x",b&255));return result.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);try{JSONObject report=new JSONObject().put("ok",error==null&&cases.length()==16&&failures==0&&unexpectedPreferenceCalls.get()==0).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("cases",cases).put("preferenceRequests",preferenceRequests).put("unexpectedPreferenceCalls",unexpectedPreferenceCalls.get()).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("sdk",android.os.Build.VERSION.SDK_INT).put("actualEmbeddedApkClassLoader",config!=null).put("actualPublicNewsConfigLoadAddRemove",config!=null).put("fixturePackage",getPackageName()).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("scope","Generated fixture-local SharedPreferences and actual embedded debug APK NewsConfig public methods only; no launcher owner data, providers, network, UI or optimized/API28 claim");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(report.toString(2).getBytes("UTF-8"));}}catch(Exception ignored){}runOnUiThread(this::finish);}
 protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
