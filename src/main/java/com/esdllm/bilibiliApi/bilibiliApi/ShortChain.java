package com.esdllm.bilibiliApi.bilibiliApi;


import com.esdllm.bilibiliApi.common.ShotChainInfo;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import lombok.Data;
import org.apache.http.HttpResponse;

import java.io.IOException;

/**
 * @author 饿死的流浪猫
 * 哔哩哔哩短链解析
 */

@Data
public class ShortChain {
    private ShotChainInfo shotChainInfo;
    public ShortChain(String shortChainUrl){
        this.shotChainInfo =getShotChainInfo(shortChainUrl);
    }
    public ShortChain(){
    }
    /**
     * 解析短链
     * @param shortChainUrl 短链地址
     * @return ShotChainInfo 短链信息
     */
    public ShotChainInfo getShotChainInfo(String shortChainUrl){
        HttpResponse response;
        try {
            response = ApiBase.getHttpResponseNotRedirect(shortChainUrl);
        } catch (IOException e) {
            throw new BilibiliException("短链解析失败"+e);
        }
        String location;
        try {
            location = response.getFirstHeader("Location").getValue();
        } catch (Exception e) {
            return null;
        }
        String[] arrayStr = location.split("/");
        return getShotChainInfo(arrayStr, location);

    }

    private  ShotChainInfo getShotChainInfo(String[] arrayStr, String location) {
        int indexOf = arrayStr[arrayStr.length - 1].indexOf("?");
        if (indexOf<=0){
            indexOf =arrayStr[arrayStr.length-1].length();
        }
        String chainId = arrayStr[arrayStr.length-1].substring(0, indexOf);
        ShotChainInfo info = new ShotChainInfo();
        info.setChainId(chainId);
        String prefix = location.substring(0, location.lastIndexOf('/')+1 );
        info.setPrefix(prefix);
        if (arrayStr[arrayStr.length-2].equals("video")){
            info.setType(1);
        }else if (arrayStr[arrayStr.length-2].equals("opus")){
            info.setType(2);
        }else if (isLive(arrayStr)){
            info.setType(0);
        }else if (arrayStr[arrayStr.length-2].equals("play")){
            info.setType(3);
        }else if (isSpace(arrayStr)){
            info.setType(4);
        }else if (arrayStr[arrayStr.length-2].equals("audio")){
            info.setType(5);
        }else {
            info.setType(6);
        }
        return info;
    }

    private Boolean isLive(String[] arrStr){
        return arrStr[2].startsWith("live")||arrStr[2].startsWith("www.live");
    }
    private Boolean isSpace(String[] arrStr){
        return arrStr[2].startsWith("space")||arrStr[2].startsWith("www.space");
    }

    /**
     * 获取短链类型
     * @param url 短链地址
     * @return 类型名称
     */
     public  String getShotChainType(String url){
        ShotChainInfo shotChainInfo = getShotChainInfo(url);
        if (shotChainInfo==null){
            return "未知";
        }
         return getString(shotChainInfo);
     }
     /**
      * 获取短链类型
      * @param info 短链信息
      * @return 类型名称
      */
     public String getShotChainType(ShotChainInfo info){
         return getString(info);
     }
    /**
     * 获取短链类型
     * @return 类型名称
     */
     public String getShotChainType(){
         return getString(shotChainInfo);
     }

    private String getString(ShotChainInfo info) {
        int type = info.getType();
        return switch (type) {
            case 0 -> "直播";
            case 1 -> "视频";
            case 2 -> "动态";
            case 3 -> "番剧";
            case 4 -> "空间";
            case 5 -> "音频";
            case 6 -> "其他";
            default -> "未知";
        };
    }
    private String getString(int type){
         ShotChainInfo info = new ShotChainInfo();
         info.setType(type);
         return getString(info);
    }
    public VideoInfo getVideoInfo(){
         if (shotChainInfo==null||shotChainInfo.getType()!=1){
             throw new BilibiliException("不是视频");
         }
        try {
            if (shotChainInfo.getChainId().startsWith("BV")) {
                return new BilibiliClient().getVideoInfo(shotChainInfo.getChainId());
            }else{
                 Long av = Long.parseLong(shotChainInfo.getChainId().substring(2));
                 return new BilibiliClient().getVideoInfo(av);
            }
        } catch (IOException e) {
            throw new BilibiliException("短链解析失败"+e);
        }
    }
    public LiveRoom getLiveRoom(){
         if (shotChainInfo==null||shotChainInfo.getType()!=0){
             throw new BilibiliException("不是直播");
         }
         Long roomId = Long.parseLong(shotChainInfo.getChainId());
         return new Live().getLiveRoom(roomId);
    }
    public BilibiliDynamicResp.Data.Card getDynamicCard(){
         if (shotChainInfo.getType()!=2){
             throw new BilibiliException("不是动态");
         }
        BilibiliDynamicResp.Data.Card card;
        try {
            card = new Dynamic().getDynamicDetail(shotChainInfo.getChainId());
        } catch (IOException e) {
            throw new BilibiliException("短链解析失败"+e);
        }
        return card;
    }
}
