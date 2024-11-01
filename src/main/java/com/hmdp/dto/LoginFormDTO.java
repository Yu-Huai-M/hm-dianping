package com.hmdp.dto;

import lombok.Data;

@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;

    public LoginFormDTO(String phone, String code, String password) {
        this.phone = phone;
        this.code = code;
        this.password = password;
    }
}
