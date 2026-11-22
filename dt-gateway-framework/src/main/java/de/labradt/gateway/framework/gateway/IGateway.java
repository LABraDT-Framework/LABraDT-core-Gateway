package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.model.SubmodelElement;

public interface IGateway {

    SubmodelElement read(String idShort);

    void write(String idShort, Object value);
}
