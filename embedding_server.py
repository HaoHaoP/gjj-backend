"""BGE-M3 Embedding HTTP Server — compatible with TEI API format"""
import json, os
from flask import Flask, request, jsonify
from sentence_transformers import SentenceTransformer

MODEL_PATH = os.environ.get("MODEL_PATH", "/models/bge-m3")

app = Flask(__name__)
print(f"Loading BGE-M3 from {MODEL_PATH} ...")
model = SentenceTransformer(MODEL_PATH, device="cpu")
print("Model loaded.")

@app.route("/encode", methods=["POST"])
def encode():
    data = request.get_json()
    sentences = data.get("sentences", [])
    if not sentences:
        return jsonify({"error": "missing 'sentences'"}), 400
    embeddings = model.encode(sentences, normalize_embeddings=True)
    return jsonify({"encodings": [e.tolist() for e in embeddings]})

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8002)
