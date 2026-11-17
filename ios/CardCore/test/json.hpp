// A tiny JSON reader for the parity test.
#pragma once
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
namespace json {
struct Value {
    enum T { NUL, BOOL, NUM, STR, ARR, OBJ } t = NUL;
    bool b = false; double n = 0; std::string s;
    std::vector<Value> a; std::map<std::string, Value> o;
    const Value& operator[](size_t i) const { return a.at(i); }
    const Value& operator[](const std::string& k) const { static Value nul; auto it = o.find(k); return it == o.end() ? nul : it->second; }
    const Value& operator[](const char* k) const { return (*this)[std::string(k)]; }
    const Value& operator[](int i) const { return a.at(static_cast<size_t>(i)); }
    bool has(const std::string& k) const { return o.count(k) > 0; }
    size_t size() const { return t == ARR ? a.size() : o.size(); }
    double num() const { return n; } const std::string& str() const { return s; } bool boolean() const { return b; }
};
struct Parser {
    const std::string& s; size_t i = 0;
    void ws() { while (i < s.size() && isspace(static_cast<unsigned char>(s[i]))) i++; }
    Value parse() {
        ws(); Value v;
        if (s[i] == '{') { v.t = Value::OBJ; i++; ws(); if (s[i] == '}') { i++; return v; }
            while (true) { ws(); std::string k = str(); ws(); i++; v.o[k] = parse(); ws(); if (s[i] == ',') { i++; continue; } i++; break; } }
        else if (s[i] == '[') { v.t = Value::ARR; i++; ws(); if (s[i] == ']') { i++; return v; }
            while (true) { v.a.push_back(parse()); ws(); if (s[i] == ',') { i++; continue; } i++; break; } }
        else if (s[i] == '"') { v.t = Value::STR; v.s = str(); }
        else if (s.compare(i, 4, "true") == 0) { v.t = Value::BOOL; v.b = true; i += 4; }
        else if (s.compare(i, 5, "false") == 0) { v.t = Value::BOOL; i += 5; }
        else if (s.compare(i, 4, "null") == 0) { i += 4; }
        else { v.t = Value::NUM; size_t e; v.n = std::stod(s.substr(i, 40), &e); i += e; }
        return v;
    }
    std::string str() {
        std::string o; i++;
        while (s[i] != '"') { if (s[i] == '\\') { i++; char c = s[i]; if (c == 'u') { o += '?'; i += 4; } else o += (c == 'n' ? '\n' : c == 't' ? '\t' : c); i++; } else o += s[i++]; }
        i++; return o;
    }
};
inline Value parse(const std::string& s) { Parser p{s}; return p.parse(); }
}
