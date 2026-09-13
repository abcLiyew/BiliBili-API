package com.esdllm.bilibiliApi.exception;

import lombok.Getter;

@Getter
public class BilibiliException extends RuntimeException {
   private final int  code;
   private final String message;
   private final String description;
   public BilibiliException(String message) {
     super(message);
     this.code=0;
     this.message = message;
     this.description = "";
   }
   public BilibiliException(Exception e){
       super(e);
       this.code = -1;
       this.message = e.getMessage();
       this.description = "";
   }
    public BilibiliException(Exception e,String description){
        super(e);
        this.code = -1;
        this.message = e.getMessage();
        this.description = description;
    }
   /**
    * 带 B 站业务错误码的构造器。
    *
    * <p>code 语义：{@code 0} = 本地参数校验失败；{@code -1} = 包装其它异常；
    * {@code > 0} = B 站返回的 {@code code}（也可能是 HTTP 状态码，如 412 风控）。
    * 由 {@code parse.ErrorMapper} 统一构造。
    */
   public BilibiliException(int code, String message, String description) {
     super(message);
     this.code = code;
     this.message = message;
     this.description = description;
   }

   /**
    * 带业务错误码 + 原始异常。
    */
   public BilibiliException(int code, String message, String description, Throwable cause) {
     super(message, cause);
     this.code = code;
     this.message = message;
     this.description = description;
   }
}