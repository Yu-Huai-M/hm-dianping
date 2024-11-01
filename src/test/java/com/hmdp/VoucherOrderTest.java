package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Random;

@SpringBootTest
public class VoucherOrderTest {
    @Resource
    private IUserService userService;

    public static int getNum(int start,int end) {
        return (int)(Math.random()*(end-start+1)+start);
    }
    private static String[] telFirst="134,135,136,137,138,139,150,151,152,157,158,159,130,131,132,155,156,133,153".split(",");
    private static String getTel() {
        int index=getNum(0,telFirst.length-1);
        String first=telFirst[index];
        String second=String.valueOf(getNum(1,888)+10000).substring(1);
        String third=String.valueOf(getNum(1,9100)+10000).substring(1);
        return first+second+third;
    }
    @Test
    void testLogin() throws IOException {
        String tokens="";
        for(int i=0;i<200;i++){
            String phone=getTel();
            System.out.println(phone);
            String code="000000";
            String password="";
            LoginFormDTO loginFormDTO=new LoginFormDTO(phone,code,password);
            HttpSession session=null;
            String token = userService.login(loginFormDTO, session).getData().toString()+"\n";
            tokens+=token;
        }
        System.out.println(tokens);
        try{
            File file = new File("tokens.txt");
            //if file doesnt exists, then create it
            if(!file.exists()){
                file.createNewFile();
            }
            //true = append file
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            fileWritter.write(tokens);
            fileWritter.close();
            System.out.println("Done");
        }catch(IOException e){
            e.printStackTrace();
        }

    }
}
