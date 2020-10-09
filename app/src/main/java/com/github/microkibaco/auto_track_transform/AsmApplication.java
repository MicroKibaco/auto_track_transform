package com.github.microkibaco.auto_track_transform;

import android.app.Application;

import com.github.microkibaco.asm_sdk.SensorsDataAPI;

/**
 * @author 杨正友(小木箱)于 2020/10/9 20 26 创建
 * @Email: yzy569015640@gmail.com
 * @Tel: 18390833563
 * @function description:
 */
public class AsmApplication extends Application {


    @Override
    public void onCreate() {
        super.onCreate();

        SensorsDataAPI.init(this);
    }
}
