package com.medtech.vitalsmanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VitalsManagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(VitalsManagementApplication.class, args);
	}

}
//docker run -it --rm eclipse-mosquitto mosquitto_sub -h host.docker.internal -p 1884 -t "sensors/vitals/test" -v
//docker run -it --rm eclipse-mosquitto mosquitto_pub -h host.docker.internal -p 1884 -t "sensors/vitals/test" -m "{\"patientId\":\"P1\",\"heartRate\":80,\"bloodPressureSystolic\":120,\"bloodPressureDiastolic\":80,\"temperature\":36.6,\"oxygenSaturation\":98}"
