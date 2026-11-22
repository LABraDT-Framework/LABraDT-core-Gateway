package de.labradt.gateway.framework.model;

/**
 * List of planned development protocols for expansion. Currently MQTT is openly supported.
 * TODO: This list should be expanded by your implementation if you add a novel gateway technology adapter.
 */
public enum ProtocolType {
    OPC_UA,
    MQTT,
    MODBUS,
    DDS,
    PROFINET
}