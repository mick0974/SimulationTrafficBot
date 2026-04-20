package com.sch.demonstrator.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public record EVModel(@JsonProperty("brand") String brand, @JsonProperty("model") String model,
                      @JsonProperty("top_speed_kmh") int topSpeedKmh,
                      @JsonProperty("battery_capacity_kWh") double batteryCapacityKWh,
                      @JsonProperty("battery_type") String batteryType,
                      @JsonProperty("number_of_cells") int numberOfCells, @JsonProperty("torque_nm") int torqueNm,
                      @JsonProperty("efficiency_wh_per_km") int efficiencyWhPerKm,
                      @JsonProperty("range_km") int rangeKm,
                      @JsonProperty("acceleration_0_100_s") double acceleration0To100Sec,
                      @JsonProperty("fast_charging_power_kw_dc") double fastChargingPowerKwDc,
                      @JsonProperty("fast_charge_port") String fastChargePort,
                      @JsonProperty("towing_capacity_kg") int towingCapacityKg,
                      @JsonProperty("cargo_volume_l") String cargoVolumeL, @JsonProperty("seats") int seats,
                      @JsonProperty("drivetrain") String drivetrain, @JsonProperty("segment") String segment,
                      @JsonProperty("length_mm") int lengthMm, @JsonProperty("width_mm") int widthMm,
                      @JsonProperty("height_mm") int heightMm, @JsonProperty("car_body_type") String carBodyType,
                      @JsonProperty("source_url") String sourceUrl) {
}
