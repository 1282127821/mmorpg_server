package com.qworldr.mmorpg.logic.account.protocal;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import com.qworldr.mmorpg.annotation.Protocal;
import com.qworldr.mmorpg.common.protocal.ProtocalId;

@Protocal(ProtocalId.LoginReq)
@ProtobufClass
public class LoginReq {
    private String account;
    private String pwd;

    public String getAccount() {
        return account;
    }

    public String getPwd() {
        return pwd;
    }
}