package com.itdl.common.enums;

import com.itdl.common.base.ResultCode;
import com.itdl.common.exception.BizException;
import lombok.Getter;

/**
 * @Description 业务类型枚举值
 * @Author itdl
 * @Date 2022/08/11 10:15
 */
@Getter
public enum BusinessType implements BaseEnums<String, String>{
    CREATE_ORDER("create_order", "创建订单");

    /**键和值定义为code, value 实现BaseEnums+@Getter完成get方法*/
    private final String code;
    private final String value;

    BusinessType(String code, String value) {
        this.code = code;
        this.value = value;
    }

    /**
     * 校验编码code是否存在
     * @param code code
     */
    public static void checkCode(String code){
        for (BusinessType value : BusinessType.values()) {
            if (!value.code.equals(code)){
                throw new BizException(ResultCode.BUSINESS_TYPE_ERR);
            }
        }
    }
}
