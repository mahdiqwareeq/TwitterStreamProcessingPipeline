import json
import time
from kafka import KafkaProducer
from typing import List, Dict
import os
from dotenv import load_dotenv

load_dotenv(override=True)
JSON_FILE_PATH = os.getenv('JSON_FILE_PATH', 'boulder_flood_geolocated_tweets.json')

class JSONToKafkaProducer:
    def __init__(self, 
                 file_path: str, 
                 kafka_bootstrap_servers: List[str] = ['localhost:9092'], 
                 kafka_topic: str = 'raw_tweets_topic'):

        self.file_path = file_path
        self.kafka_topic = kafka_topic
        
        # Initialize Kafka Producer
        self.producer = KafkaProducer(
            bootstrap_servers=kafka_bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode('utf-8')
        )
    
    def read_file(self) -> List[Dict]:
        file_obj = open(self.file_path, "r") 
  
        # reading the data from the file 
        file_data = file_obj.read()
        #Remve missy new line characters
        file_data = file_data.replace("\\n", " ") 
        # splitting the file data into lines 
        lines = file_data.splitlines()         
        file_obj.close() 
        return lines
    
    def stream_to_kafka(self, interval_seconds: int = 60):
        records = self.read_file()        
        recordsCount = len(records)
        chunkSize = 100
        for i in range(0, recordsCount, chunkSize):
            chunkSize = min(chunkSize, recordsCount - i)
            messagesToSent = records[i:i+chunkSize]                
            message = "\n".join(messagesToSent)
            try:                
                # Send record to Kafka topic
                self.producer.send(self.kafka_topic, message, timestamp_ms=int(time.time()))
                print(f"Sent record: {chunkSize}")
            except Exception as e:
                print(f"Error sending record to Kafka: {e}")
            
            time.sleep(interval_seconds)
                
        self.producer.flush()        
            
    
    def __del__(self):
        if hasattr(self, 'producer'):
            self.producer.close()

def main():
    # Create Producer
    producer = JSONToKafkaProducer(JSON_FILE_PATH)
    
    # Start streaming (Stream every 60 seconds)
    producer.stream_to_kafka(interval_seconds=10)

if __name__ == "__main__":
    main()
