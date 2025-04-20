from flask import Flask

def create_app():
    # Initialize your application
    app = Flask(__name__)
    
    @app.route('/')
    def index():
        return "Amazon App is running!"
    
    return app  # Return your Flask application instance
