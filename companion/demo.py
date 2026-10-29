"""Small, synthetic offline sample for the distributable native self-test."""


def snapshot():
    return {"format": "manhwa-full", "version": 1, "exportedAt": 1,
            "library": {"format": "manhwa-takip", "version": 4, "exportedAt": 1,
                        "records": [{"id": "11111111-1111-4111-8111-111111111111", "title": "Türkçe Örnek",
                                     "lastChapter": "8", "updatedAt": 2, "status": "READING",
                                     "notes": "", "categories": [], "local": {"favorite": False}}],
                        "categoryDefinitions": []}, "history": [], "chats": [], "preferences": {}}
