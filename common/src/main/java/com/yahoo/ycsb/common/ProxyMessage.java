package com.yahoo.ycsb.common;

import java.io.Serializable;

/**
 * Created by Raluca on 14.03.16.
 */
public abstract class ProxyMessage implements Serializable {

    protected ProxyMessageType type;

    public ProxyMessageType getType() {
        return type;
    }

    public abstract String prettyPrint();
}
