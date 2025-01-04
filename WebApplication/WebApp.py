from flask import Flask, render_template, jsonify, request
from pymongo import MongoClient
import os
from dotenv import load_dotenv
from datetime import datetime


load_dotenv(override=True)
app = Flask(__name__)
MONGO_URI = os.getenv('MONGO_URI', 'mongodb://localhost:27017/')
DATABASE_NAME = os.getenv('MONGO_DATABASE', 'tweets_db')
COLLECTION_NAME = os.getenv('MONGO_COLLECTION', 'tweets')

def get_mongodb_connection():
    try:
        client = MongoClient(MONGO_URI)
        db = client[DATABASE_NAME]
        return db[COLLECTION_NAME]
    except Exception as e:
        print(f"MongoDB Connection Error: {e}")
        return None

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/search', methods=['POST'])
def search_tweets():
    collection = get_mongodb_connection()    
    if collection is None:
        return jsonify({"error": "Database connection failed"}), 500
    
    # Get search parameters
    search_data = request.json
    search_term = search_data.get('search_term', '').lower()
    trend_start_date = search_data.get('trend_start_date')
    trend_end_date = search_data.get('trend_end_date')
    
    # Keyword search match stage
    search_match_stage = {'$match': {}}
    if search_term:
        search_match_stage['$match']['$or'] = [
            {'text': {'$regex': search_term, '$options': 'i'}},
            {'user_name': {'$regex': search_term, '$options': 'i'}},
            {'hashtags': {'$elemMatch': {'$regex': search_term, '$options': 'i'}}}
        ]
    
    # Trends match stage
    trends_match_stage = {'$match': {}}
    if trend_start_date and trend_end_date:
        try:
            start_date = datetime.fromisoformat(trend_start_date)
            end_date = datetime.fromisoformat(trend_end_date)
            trends_match_stage['$match']['created_at'] = {
                '$gte': start_date,
                '$lte': end_date
            }
        except ValueError:
            pass
    
    # Search results pipeline
    search_pipeline = [
        search_match_stage,
        trends_match_stage,
        {
            '$project': {
                '_id': 0,
                'created_at': 1,
                'tweet_id': 1,
                'text': 1,
                'hashtags': 1,
                'user_name': 1,
                'longitude': 1,
                'latitude': 1,
                'sentimentScore': 1
            }
        }
    ]
    
    # Hourly trends pipeline
    hourly_trends_pipeline = [
        search_match_stage,
        trends_match_stage,
        {
            '$group': {
                '_id': {
                    'date': {
                        '$dateToString': {
                            'format': '%Y-%m-%d',
                            'date': {'$toDate': '$created_at'}
                        }
                    },
                    'hour': {'$hour': {'$toDate': '$created_at'}}
                },
                'tweet_count': {'$sum': 1}
            }
        },
        {
            '$project': {
                '_id': 0,
                'date': '$_id.date',
                'hour': '$_id.hour',
                'tweet_count': 1
            }
        },
        {'$sort': {'date': 1, 'hour': 1}}
    ]    
    
    # Daily trends pipeline
    daily_trends_pipeline = [
        search_match_stage,
        trends_match_stage,
        {
            '$group': {
                '_id': {
                    '$dateToString': {
                        'format': '%Y-%m-%d', 
                        'date': {'$toDate': '$created_at'}
                    }
                },
                'tweet_count': {'$sum': 1}
            }
        },
        {
            '$project': {
                '_id': 0,
                'date': '$_id',
                'tweet_count': 1
            }
        },
        {'$sort': {'date': 1}}
    ]
    
    # Sentiment analysis pipeline
    sentiment_pipeline = [
        search_match_stage,
        trends_match_stage,
        {
            '$group': {
                '_id': None,
                'total_tweets': {'$sum': 1},
                'positive_tweets': {
                    '$sum': {
                        '$cond': [
                            {'$eq': ['$sentimentScore', 'positive']}, 
                            1, 
                            0
                        ]
                    }
                }
            }
        }
    ]
    
    # Execute aggregations
    search_results = list(collection.aggregate(search_pipeline))
    hourly_trends = list(collection.aggregate(hourly_trends_pipeline))
    daily_trends = list(collection.aggregate(daily_trends_pipeline))
    sentiment_results = list(collection.aggregate(sentiment_pipeline))
    
    # Process sentiment data
    sentiment_data = sentiment_results[0] if sentiment_results else {
        'total_tweets': 0,
        'positive_tweets': 0
    }
    positive_percentage = (
        (sentiment_data['positive_tweets'] / sentiment_data['total_tweets'] * 100) 
        if sentiment_data['total_tweets'] > 0 else 50
    )
    
    # Response
    response = {
        'locations': search_results,
        'total_tweets': len(search_results),
        'unique_users': len({tweet['user_name'] for tweet in search_results}),
        'date_range': {
            'start': min(tweet['created_at'] for tweet in search_results) if search_results else None,
            'end': max(tweet['created_at'] for tweet in search_results) if search_results else None
        },
        'trends': {
            'hourly': hourly_trends,
            'daily': daily_trends
        },
        'sentiment': {
            'total_tweets': sentiment_data['total_tweets'],
            'positive_tweets': sentiment_data['positive_tweets'],
            'positive_percentage': positive_percentage
        }
    }
    
    return jsonify(response)

if __name__ == '__main__':
    app.run(debug=True)