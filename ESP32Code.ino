#include <BluetoothSerial.h>

BluetoothSerial SerialBT;

// GPIO pins for LEDs (safe pins that don't interfere with boot)
int ledPins[] = {13, 12, 14, 27, 26};  // Thumb, Index, Middle, Ring, Pinky
const int NUM_LEDS = 5;

void setup() {
  Serial.begin(9600);
  SerialBT.begin("RoomMates AI");
  Serial.println("ESP32 Bluetooth Started!");
  Serial.println("Device Name: RoomMates AI");
  
  // Clear any startup noise in buffer
  while(SerialBT.available()) {
    SerialBT.read();
  }
  
  // Initialize all LED pins
  for (int i = 0; i < NUM_LEDS; i++) {
    pinMode(ledPins[i], OUTPUT);
    digitalWrite(ledPins[i], LOW);  // Start with all LEDs OFF
  }
  
  // Test sequence - blink all LEDs to confirm wiring
  Serial.println("Testing LEDs...");
  for (int i = 0; i < NUM_LEDS; i++) {
    digitalWrite(ledPins[i], HIGH);
    delay(200);
    digitalWrite(ledPins[i], LOW);
  }
  Serial.println("Ready for gesture commands!");
}

void loop() {
  // Check if we have exactly 5 bytes of finger data
  if (SerialBT.available() >= 5) {
    byte fingerData[5];
    
    // Read all 5 bytes into buffer
    for (int i = 0; i < 5; i++) {
      fingerData[i] = SerialBT.read();
    }
  
    // Apply the LED states
    for (int i = 0; i < NUM_LEDS; i++) {
      digitalWrite(ledPins[i], fingerData[i] == 1 ? HIGH : LOW);
    }
    
    // Debug output to Serial Monitor
    Serial.print("Received: ");
    for (int i = 0; i < 5; i++) {
      Serial.print(fingerData[i]);
      Serial.print(" ");
    }
    Serial.println();
    
    // Clear any extra bytes that might have accumulated
    while(SerialBT.available() > 0) {
      SerialBT.read();
    }
  }
  
  delay(10);  // Small delay to prevent overwhelming the loop
}
