package com.itdl.common.base;

import com.itdl.common.enums.BaseEnums;
import lombok.Getter;

/**
 * @Description
 * @Author itdl
 * @Date 2022/08/08 10:40
 */
@Getter
public enum ResultCode implements BaseEnums<String, String> {
    /**通用业务返回码定义，系统-编码*/
    SUCCESS("interface-idempotent-000000", "success"),

    INTERFACE_REPEAT_COMMIT_ERR("interface-idempotent-000001", "请勿重复提交"),
    BUSINESS_TYPE_ERR("interface-idempotent-000002", "业务类型错误"),
    TOKEN_IS_NOT_EMPTY_ERR("interface-idempotent-000003", "Token不能为空"),

    SQL_EXEC_ERR("interface-idempotent-000005", "执行SQL错误"),
    LUA_SCRIPT_EXEC_ERR("interface-idempotent-000005", "执行Lua脚本错误"),



    SYSTEM_INNER_ERR("db-conn-100000", "系统内部错误"),
    ;

    /**键和值定义为code, value 实现BaseEnums+@Getter完成get方法*/
    private final String code;
    private final String value;

    ResultCode(String code, String value) {
        this.code = code;
        this.value = value;
    }
}
