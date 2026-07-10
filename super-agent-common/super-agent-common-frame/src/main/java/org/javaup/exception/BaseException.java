package org.javaup.exception;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 异常类
 * @author: wangpeng
 **/

public class BaseException extends RuntimeException{

	public BaseException() {

	}

	public BaseException(String message) {
		super(message);
	}

	public BaseException(Throwable cause) {
		super(cause);
	}

	public BaseException(String message, Throwable cause) {
		super(message, cause);
	}

	public BaseException(Integer code, String message, Throwable cause) {
		super(message, cause);
	}
}
