// The data pack for the Linux parity test (the iPhone app loads it in Swift).
#pragma once
#include "cardcore.hpp"
#include <sqlite3.h>
#include <cstring>
#include <fstream>
#include <map>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

inline float halfToFloat(uint16_t h) {
    uint32_t sign = (h & 0x8000u) << 16, exp = (h >> 10) & 0x1f, mant = h & 0x3ffu, f;
    if (exp == 0) {
        if (mant == 0) f = sign;
        else { exp = 127 - 15 + 1; while (!(mant & 0x400u)) { mant <<= 1; exp--; } mant &= 0x3ffu; f = sign | (exp << 23) | (mant << 13); }
    } else if (exp == 31) f = sign | 0x7f800000u | (mant << 13);
    else f = sign | ((exp + 127 - 15) << 23) | (mant << 13);
    float out; std::memcpy(&out, &f, 4); return out;
}

struct PackData {
    cardcore::Geometry geometry{};
    float sureMargin = 0.085f;
    int n = 0, dim = 0;
    std::vector<float> index;
    std::vector<std::string> cardOf;
    std::vector<int> groupIndex;
    std::vector<std::string> groupName;
    std::unordered_map<std::string, std::string> cardGroup;
    std::string groupOfCard(const std::string& card) const { auto it = cardGroup.find(card); return it == cardGroup.end() ? "" : it->second; }
};

inline PackData loadPack(const std::string& dir) {
    PackData p;
    sqlite3* db = nullptr;
    if (sqlite3_open_v2((dir + "/catalog.sqlite").c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) throw std::runtime_error("catalog");
    std::map<std::string, std::string> meta;
    sqlite3_stmt* st;
    sqlite3_prepare_v2(db, "SELECT key, value FROM meta", -1, &st, nullptr);
    while (sqlite3_step(st) == SQLITE_ROW)
        meta[reinterpret_cast<const char*>(sqlite3_column_text(st, 0))] = reinterpret_cast<const char*>(sqlite3_column_text(st, 1));
    sqlite3_finalize(st);
    auto I = [&](const char* k) { return std::stoi(meta.at(k)); };
    p.dim = I("dim"); p.n = I("entries");
    auto& g = p.geometry;
    g.rectW = I("rect_w"); g.rectH = I("rect_h"); g.cardW = I("card_w"); g.cardH = I("card_h");
    g.artX = I("art_x"); g.artY = I("art_y"); g.artW = I("art_w"); g.artH = I("art_h"); g.input = I("input");
    std::string box = meta.count("art_box") ? meta["art_box"] : "0.07,0.09,0.93,0.5";
    std::sscanf(box.c_str(), "%lf,%lf,%lf,%lf", &g.artBox[0], &g.artBox[1], &g.artBox[2], &g.artBox[3]);
    if (meta.count("margin_99")) p.sureMargin = std::stof(meta["margin_99"]);
    p.cardOf.resize(p.n); p.groupIndex.resize(p.n);
    std::unordered_map<std::string, int> gi;
    sqlite3_prepare_v2(db, "SELECT idx, card_id, grp FROM entries", -1, &st, nullptr);
    while (sqlite3_step(st) == SQLITE_ROW) {
        int i = sqlite3_column_int(st, 0);
        std::string card = reinterpret_cast<const char*>(sqlite3_column_text(st, 1));
        std::string grp = reinterpret_cast<const char*>(sqlite3_column_text(st, 2));
        p.cardOf[i] = card;
        auto it = gi.find(grp);
        if (it == gi.end()) { it = gi.emplace(grp, static_cast<int>(p.groupName.size())).first; p.groupName.push_back(grp); }
        p.groupIndex[i] = it->second;
        p.cardGroup.emplace(card, grp);
    }
    sqlite3_finalize(st);
    sqlite3_close(db);
    std::ifstream f(dir + "/index.f16", std::ios::binary);
    std::vector<uint16_t> h(static_cast<size_t>(p.n) * p.dim);
    f.read(reinterpret_cast<char*>(h.data()), static_cast<std::streamsize>(h.size() * 2));
    p.index.resize(h.size());
    for (size_t i = 0; i < h.size(); i++) p.index[i] = halfToFloat(h[i]);
    return p;
}
