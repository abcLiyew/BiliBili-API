package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * <b>临时探针</b>（默认跳过）：dump 真实 desktop feed 响应里 {@code modules} 的结构，
 * 用于定位 {@code DynamicInfo.time} 恒为 null 的根因。
 *
 * <pre>
 * mvn -o -B test -Dtest=RawFeedProbeTest -Dbili.smoke=true
 * </pre>
 *
 * <p>用完即删 —— 它不是回归测试，只是排障工具。
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("临时探针：dump 真实 feed 的 modules 结构（默认跳过）")
class RawFeedProbeTest {

    @Test
    void dumpModules() {
        String url = String.format(BilibiliEndpoint.dynamicFeedUrl, "946974");

        // -352 风控是概率性的（实测同一进程内时通时断），循环穿透风控窗口
        JSONObject body = null;
        HttpResponse<String> resp = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            resp = BilibiliHttp.get(url);
            body = JSON.parseObject(resp.getBody());
            int code = body.getInteger("code") == null ? -999 : body.getInteger("code");
            System.out.printf("--- 尝试 %d：HTTP %d code=%s ---%n", attempt, resp.getStatus(), code);
            if (code == 0 && body.getJSONObject("data") != null) {
                break;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("=== 最终 HTTP " + resp.getStatus() + " ===");
        System.out.println("code=" + body.getInteger("code") + " message=" + body.getString("message"));
        JSONObject data = body.getJSONObject("data");
        if (data == null) {
            System.out.println("!!! 连续 8 次都拿到 code!=0 —— -352 风控持续生效，原始响应：" + brief(resp.getBody()));
            return;
        }
        JSONArray items = data.getJSONArray("items");
        System.out.println("items.size=" + (items == null ? "null" : items.size()));
        if (items == null || items.isEmpty()) {
            System.out.println("!!! items 空，data 的键：" + data.keySet());
            return;
        }

        for (int i = 0; i < Math.min(3, items.size()); i++) {
            JSONObject item = items.getJSONObject(i);
            System.out.println("\n--- item[" + i + "] ---");
            System.out.println("type=" + item.getString("type") + " id_str=" + item.getString("id_str"));

            Object modulesObj = item.get("modules");
            if (modulesObj == null) {
                System.out.println("  modules=null，item 的键：" + item.keySet());
                continue;
            }
            System.out.println("  modules 实际类型 = " + modulesObj.getClass().getName());

            if (modulesObj instanceof JSONArray arr) {
                for (int m = 0; m < arr.size(); m++) {
                    JSONObject mod = arr.getJSONObject(m);
                    String mt = mod.getString("module_type");
                    System.out.println("  [" + m + "] module_type=" + mt);
                    // 把每个 module 的直属子对象 dump 出来（一层）
                    for (String key : mod.keySet()) {
                        if ("module_type".equals(key)) continue;
                        Object v = mod.get(key);
                        if (v instanceof JSONObject jo) {
                            System.out.println("       " + key + " 的键 = " + jo.keySet());
                            // 对 author 模块，把关键字段值也打出来
                            if (key.contains("author")) {
                                System.out.println("         pub_text=" + jo.getString("pub_text")
                                        + " | pub_ts=" + jo.getLong("pub_ts")
                                        + " | pub_time=" + jo.getString("pub_time")
                                        + " | time=" + jo.getString("time"));
                                Object user = jo.get("user");
                                if (user instanceof JSONObject u) {
                                    System.out.println("         user 的键 = " + u.keySet());
                                }
                            }
                        }
                    }
                }
            } else if (modulesObj instanceof JSONObject modules) {
                System.out.println("  modules 的键 = " + modules.keySet());
                JSONObject author = modules.getJSONObject("module_author");
                if (author != null) {
                    System.out.println("  module_author 的键 = " + author.keySet());
                    System.out.println("    pub_text=" + author.getString("pub_text")
                            + " | pub_ts=" + author.getLong("pub_ts")
                            + " | pub_time=" + author.getString("pub_time")
                            + " | pub_action=" + author.getString("pub_action")
                            + " | is_top=" + author.get("is_top"));
                }
                JSONObject tag = modules.getJSONObject("module_tag");
                if (tag != null) {
                    System.out.println("  module_tag 的键 = " + tag.keySet()
                            + " | text=" + tag.getString("text"));
                }
                JSONObject dyn = modules.getJSONObject("module_dynamic");
                if (dyn != null) {
                    System.out.println("  module_dynamic 的键 = " + dyn.keySet());
                    JSONObject major = dyn.getJSONObject("major");
                    if (major != null) {
                        System.out.println("    major.type = " + major.getString("type")
                                + " | major 的键 = " + major.keySet());
                        String mt = major.getString("type");
                        if ("MAJOR_TYPE_ARCHIVE".equals(mt)) {
                            JSONObject a = major.getJSONObject("archive");
                            System.out.println("      archive.bvid=" + (a == null ? null : a.getString("bvid"))
                                    + " | title=" + (a == null ? null : a.getString("title")));
                        } else if ("MAJOR_TYPE_DRAW".equals(mt)) {
                            JSONObject d = major.getJSONObject("draw");
                            int n = 0;
                            if (d != null && d.getJSONArray("items") != null) {
                                n = d.getJSONArray("items").size();
                            }
                            System.out.println("      draw.items.size=" + n);
                        }
                    }
                    JSONObject desc = dyn.getJSONObject("desc");
                    if (desc != null) {
                        System.out.println("    desc.text=" + brief(desc.getString("text")));
                    }
                }
            } else {
                System.out.println("  modules 类型意外：" + modulesObj.getClass());
            }
        }
    }

    private static String brief(String s) {
        if (s == null) return "null";
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() <= 800 ? flat : flat.substring(0, 800) + "...";
    }
}