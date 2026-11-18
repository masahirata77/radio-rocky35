// Curated from original Radio Browser snapshot, November 2026.
// Department only; unresolved entries kept with null department.
const fs=require('fs');
const s=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
if(s.length!==123||s[0].name!=='Carve Deportiva'||s[122].name!=='ARAPEY (AM 1450)')throw Error('Unexpected source snapshot');
const groups={
'Montevideo':[0,1,3,4,5,6,7,8,10,15,16,17,18,19,20,21,22,25,26,28,29,30,31,32,33,34,37,39,43,45,47,48,49,53,54,55,58,65,67,68,71,72,73,76,77,79,82,84,87,89,92,98,99,101,102,111,113,114,117,14],
'Canelones':[9,41,50,51,81,97],
'Florida':[2,23,63,69],
'Paysandú':[13,44,56,57,61,70,74,86,94,105,108],
'Soriano':[11],
'San José':[35,115],
'Salto':[38,88,107,120,122],
'Tacuarembó':[40,46,85],
'Treinta y Tres':[42,52,93,100],
'Cerro Largo':[36,64,80,106,110],
'Maldonado':[59,83,109],
'Colonia':[60,116],
'Rocha':[78],
'Rivera':[12,90],
'Río Negro':[103,112,118,121],
'Artigas':[104]
};
const assigned=new Map();
for(const [d,ids] of Object.entries(groups))for(const i of ids){if(assigned.has(i))throw Error('Duplicate assignment '+i);assigned.set(i,d)}
const merge=[[1,21],[4,16],[6,48,65],[9,41],[3,45],[25,99],[44,94],[57,74],[87,89]];
const skip=new Set();
for(const [a,...rest] of merge){s[a].aliases=rest.map(i=>s[i].id);s[a].streams=[...new Set([s[a].url,s[a].original,...rest.flatMap(i=>[s[i].url,s[i].original])].filter(Boolean))];rest.forEach(i=>skip.add(i))}
s[29].name='Radio Carve 850 AM'; // distinct stream from Carve Deportiva
s[6].name='Océano FM 93.9';s[3].name='Del Sol FM 99.5';s[9].name='Inolvidable FM 93.1';s[25].name='Radio Disney Uruguay';s[4].name='Radio Pipí Cucú';s[50].name='Cauces FM 89.1';
const out=s.flatMap((x,i)=>skip.has(i)?[]:[{...x,department:assigned.get(i)||null}]);
fs.writeFileSync('stations.json',JSON.stringify(out,null,2)+'\n');
console.log(out.length,'stations;',out.filter(x=>!x.department).length,'unconfirmed:',out.filter(x=>!x.department).map(x=>x.name).join(', '));
