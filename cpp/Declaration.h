
#include <string>
#include <map>
#include <memory>
#include "BSVType.h"

class Declaration;

class EnumDeclaration;

class EnumElementDeclaration;

class MethodDeclaration;

class ModuleDeclaration;

class StructDeclaration;

class StructMemberDeclaration;

class UnionDeclaration;

class UnionMemberDeclaration;

class Declaration {
public:
    const std::string name;
    const std::shared_ptr<BSVType> bsvtype;

    Declaration(std::string name, std::shared_ptr<BSVType> bsvtype) : name(name), bsvtype(bsvtype) {};

    virtual ~Declaration() {}
};

class EnumDeclaration : public Declaration {
public:
    std::vector<std::shared_ptr<EnumElementDeclaration> > tags;

    EnumDeclaration(std::string name, std::shared_ptr<BSVType> bsvtype) : Declaration(name, bsvtype) {};
};